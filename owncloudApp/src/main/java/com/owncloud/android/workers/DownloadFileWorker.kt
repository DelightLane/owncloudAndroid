/**
 * ownCloud Android client application
 *
 * @author Abel García de Prada
 * @author Juan Carlos Garrote Gascón
 *
 * Copyright (C) 2024 ownCloud GmbH.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.owncloud.android.workers

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import at.bitfire.dav4jvm.exception.UnauthorizedException
import com.owncloud.android.R
import com.owncloud.android.data.executeRemoteOperation
import com.owncloud.android.data.providers.LocalStorageProvider
import com.owncloud.android.domain.exceptions.CancelledException
import com.owncloud.android.domain.exceptions.LocalStorageNotMovedException
import com.owncloud.android.domain.exceptions.LocalStoragePermissionRequiredException
import com.owncloud.android.domain.exceptions.NetworkErrorException
import com.owncloud.android.domain.exceptions.NoConnectionWithServerException
import com.owncloud.android.domain.exceptions.NoNetworkConnectionException
import com.owncloud.android.domain.exceptions.ServerConnectionTimeoutException
import com.owncloud.android.domain.exceptions.ServerNotReachableException
import com.owncloud.android.domain.exceptions.ServerResponseTimeoutException
import com.owncloud.android.domain.exceptions.ServiceUnavailableException
import com.owncloud.android.domain.files.model.OCFile
import com.owncloud.android.domain.files.usecases.CleanConflictUseCase
import com.owncloud.android.domain.files.usecases.CleanWorkersUUIDUseCase
import com.owncloud.android.domain.files.usecases.GetFileByIdUseCase
import com.owncloud.android.domain.files.usecases.GetWebDavUrlForSpaceUseCase
import com.owncloud.android.domain.files.usecases.SaveDownloadWorkerUUIDUseCase
import com.owncloud.android.domain.files.usecases.SaveFileOrFolderUseCase
import com.owncloud.android.lib.common.OwnCloudAccount
import com.owncloud.android.lib.common.OwnCloudClient
import com.owncloud.android.lib.common.SingleSessionManager
import com.owncloud.android.lib.common.network.OnDatatransferProgressListener
import com.owncloud.android.lib.resources.files.DownloadRemoteFileOperation
import com.owncloud.android.presentation.authentication.ACTION_UPDATE_EXPIRED_TOKEN
import com.owncloud.android.presentation.authentication.AccountUtils
import com.owncloud.android.presentation.authentication.EXTRA_ACCOUNT
import com.owncloud.android.presentation.authentication.EXTRA_ACTION
import com.owncloud.android.presentation.authentication.LoginActivity
import com.owncloud.android.presentation.transfers.TransferOperation.Download
import com.owncloud.android.ui.errorhandling.ErrorMessageAdapter
import com.owncloud.android.usecases.transfers.MAXIMUM_NUMBER_OF_RETRIES
import com.owncloud.android.utils.DOWNLOAD_NOTIFICATION_CHANNEL_ID
import com.owncloud.android.utils.DOWNLOAD_NOTIFICATION_ID_DEFAULT
import com.owncloud.android.utils.FileStorageUtils
import com.owncloud.android.utils.NOTIFICATION_TIMEOUT_STANDARD
import com.owncloud.android.usecases.transfers.MAX_CONCURRENT_DOWNLOADS
import com.owncloud.android.utils.NotificationUtils.createBasicNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.File

class DownloadFileWorker(
    private val appContext: Context,
    private val workerParameters: WorkerParameters
) : CoroutineWorker(
    appContext,
    workerParameters,
), KoinComponent, OnDatatransferProgressListener {

    private val getFileByIdUseCase: GetFileByIdUseCase by inject()
    private val saveFileOrFolderUseCase: SaveFileOrFolderUseCase by inject()
    private val getWebdavUrlForSpaceUseCase: GetWebDavUrlForSpaceUseCase by inject()
    private val cleanConflictUseCase: CleanConflictUseCase by inject()
    private val saveDownloadWorkerUuidUseCase: SaveDownloadWorkerUUIDUseCase by inject()
    private val cleanWorkersUuidUseCase: CleanWorkersUUIDUseCase by inject()
    private val localStorageProvider: LocalStorageProvider by inject()

    lateinit var account: Account
    lateinit var ocFile: OCFile

    private lateinit var downloadRemoteFileOperation: DownloadRemoteFileOperation
    private var lastPercent = 0

    /**
     * Temporal path for this file to be downloaded.
     */
    private val temporalFilePath
        get() = temporalFolderPath + ocFile.remotePath

    /**
     * Temporal path where every file of this account will be downloaded.
     */
    private val temporalFolderPath
        get() = FileStorageUtils.getTemporalPath(account.name, ocFile.spaceId)

    /**
     * Final path where this file should be stored.
     *
     * In case this file was previously downloaded, override it. Otherwise,
     * @see LocalStorageProvider.getDefaultSavePathFor
     */
    private val finalLocationForFile: String
        get() = ocFile.storagePath.takeUnless { it.isNullOrBlank() }
            ?: localStorageProvider.getDefaultSavePathFor(accountName = account.name, remotePath = ocFile.remotePath, spaceId = ocFile.spaceId)

    override suspend fun doWork(): Result {
        if (!areParametersValid()) return Result.failure()

        // Cap how many downloads are actually transferring at once (independently of how many
        // DownloadFileWorker instances WorkManager has started), instead of chaining files together into
        // unique-work lanes. Chaining made every file after a permanently failed one in the same lane get
        // silently cancelled without ever running - a single broken file could take a whole batch of
        // otherwise fine files down with it. A permit-based limit keeps every file's download independent:
        // one file's failure or retries never affects any other file's chances of being downloaded.
        return downloadSemaphore.withPermit {
            try {
                if (!isLocalStoragePermissionGranted()) throw LocalStoragePermissionRequiredException()
                downloadFileToTemporalFile()
                moveTemporalFileToFinalLocation()
                updateDatabaseWithLatestInfoForThisFile()
                notifyDownloadResult(null)
            } catch (throwable: Throwable) {
                Timber.e(throwable)
                notifyDownloadResult(throwable)
            }
        }
    }

    /**
     * Files are stored under the shared external storage root, which on API >= 30 requires the
     * special MANAGE_EXTERNAL_STORAGE ("all files access") permission, only grantable from system settings.
     */
    private fun isLocalStoragePermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /**
     * Verify that the parameters are valid.
     *
     * This verification includes (at the moment):
     * - Check whether account exists
     * - Check whether file exists in the database
     * - Check that the file is not a folder
     */
    private fun areParametersValid(): Boolean {
        val accountName = workerParameters.inputData.getString(KEY_PARAM_ACCOUNT)
        val fileId = workerParameters.inputData.getLong(KEY_PARAM_FILE_ID, -1)

        account = AccountUtils.getOwnCloudAccountByName(appContext, accountName) ?: return false
        ocFile = getFileByIdUseCase(GetFileByIdUseCase.Params(fileId)).getDataOrNull() ?: return false

        return !ocFile.isFolder
    }

    /**
     * Download the file or throw an exception if something goes wrong.
     * We will initialize a listener to update the notification according to the download progress.
     *
     * File will be downloaded to a temporalFolder in the RemoteOperation.
     * @see temporalFolderPath for the temporal location
     */
    private fun downloadFileToTemporalFile() {
        saveDownloadWorkerUuidUseCase(
            SaveDownloadWorkerUUIDUseCase.Params(
                fileId = workerParameters.inputData.getLong(KEY_PARAM_FILE_ID, -1),
                workerUuid = id
            )
        )

        val spaceWebDavUrl =
            getWebdavUrlForSpaceUseCase(GetWebDavUrlForSpaceUseCase.Params(accountName = account.name, spaceId = ocFile.spaceId))

        downloadRemoteFileOperation = DownloadRemoteFileOperation(
            ocFile.remotePath,
            temporalFolderPath,
            spaceWebDavUrl,
        ).apply {
            addDatatransferProgressListener(this@DownloadFileWorker)
        }
        val client = getClientForThisDownload()

        // It will throw an exception if something goes wrong.
        executeRemoteOperation {
            downloadRemoteFileOperation.execute(client)
        }
    }

    /**
     * Move the temporal file to the final location.
     * @see temporalFilePath for the temporal location
     * @see finalLocationForFile for the final one
     */
    private fun moveTemporalFileToFinalLocation() {
        val temporalLocation = File(temporalFilePath)

        if (FileStorageUtils.getUsableSpace() < temporalLocation.length()) {
            Timber.w("Not enough space to copy %s", temporalLocation.absolutePath)
        }

        val finalLocation = File(finalLocationForFile)
        finalLocation.parentFile?.mkdirs()
        val movedToTheFinalLocation = temporalLocation.renameTo(finalLocation)

        if (!movedToTheFinalLocation) {
            throw LocalStorageNotMovedException()
        }
    }

    /**
     * Update the database with latest details about this file.
     *
     * We will ask for thumbnails after a download
     * We will update info about the file (modification timestamp and etag)
     * We will update info about local storage (where it was stored and its size)
     */
    private fun updateDatabaseWithLatestInfoForThisFile() {
        val currentTime = System.currentTimeMillis()
        // Read back the timestamp that the filesystem actually stored for the just-moved file (instead of
        // using currentTime) so it matches exactly what OCFile.localModificationTimestamp will read later on.
        // Some filesystems used by SD cards (FAT32/exFAT) only have a couple of seconds of timestamp
        // resolution and can round it up past currentTime, which made the file look locally modified right
        // after being downloaded and triggered an immediate, spurious re-upload.
        val finalFileLastModified = File(finalLocationForFile).lastModified()
        ocFile.apply {
            needsToUpdateThumbnail = true
            modificationTimestamp = downloadRemoteFileOperation.modificationTimestamp
            etag = downloadRemoteFileOperation.etag
            storagePath = finalLocationForFile
            length = (File(finalLocationForFile).length())
            lastSyncDateForData = finalFileLastModified
            modifiedAtLastSyncForData = downloadRemoteFileOperation.modificationTimestamp
            lastUsage = currentTime
        }
        saveFileOrFolderUseCase(SaveFileOrFolderUseCase.Params(ocFile))
        cleanConflictUseCase(
            CleanConflictUseCase.Params(
                fileId = ocFile.id!!
            )
        )

        // To be done. Probably we will move it out from here.
        //mStorageManager.triggerMediaScan(file.getStoragePath())
    }

    /**
     * Notify download result and then return Worker Result.
     */
    private fun notifyDownloadResult(
        throwable: Throwable?
    ): Result {
        val willRetry = throwable != null && isTransientNetworkError(throwable) && runAttemptCount < MAXIMUM_NUMBER_OF_RETRIES

        // Only clear the "synchronizing" state when this attempt is truly done (succeeded or permanently
        // failed). Clearing it while a retry is still pending made the file (and its parent folder) briefly
        // look like it was not being synced during the backoff wait, even though WorkManager was about to
        // try again in the background.
        if (!willRetry) {
            cleanWorkersUuidUseCase(
                CleanWorkersUUIDUseCase.Params(
                    fileId = workerParameters.inputData.getLong(KEY_PARAM_FILE_ID, -1)
                )
            )
        }

        // Skip the notification when we are about to retry silently in the background, so a transient
        // hiccup while downloading a folder with many files does not spam a failure notification per file
        // per attempt; only the final outcome (success or exhausted retries) gets one.
        if (throwable !is CancelledException && !willRetry) {

            var tickerId = if (throwable == null) {
                R.string.downloader_download_succeeded_ticker
            } else {
                R.string.downloader_download_failed_ticker
            }

            var pendingIntent: PendingIntent? = null
            if (throwable is UnauthorizedException) {
                tickerId = R.string.downloader_download_failed_credentials_error
                pendingIntent = composePendingIntentToRefreshCredentials()
            } else if (throwable is LocalStoragePermissionRequiredException) {
                tickerId = R.string.downloader_download_failed_permission_error
                pendingIntent = composePendingIntentToGrantStoragePermission()
            }

            val contextText = ErrorMessageAdapter.getMessageFromTransfer(
                transferOperation = Download(finalLocationForFile),
                throwable = throwable,
                resources = appContext.resources
            )

            var timeOut: Long? = null

            // Remove success notification after timeout
            if (throwable == null) {
                timeOut = NOTIFICATION_TIMEOUT_STANDARD
            }

            createBasicNotification(
                context = appContext,
                contentTitle = appContext.getString(tickerId),
                notificationChannelId = DOWNLOAD_NOTIFICATION_CHANNEL_ID,
                notificationId = DOWNLOAD_NOTIFICATION_ID_DEFAULT,
                intent = pendingIntent,
                contentText = contextText,
                timeOut = timeOut
            )
        }

        return if (throwable == null) {
            Result.success()
        } else if (willRetry) {
            // Downloading a folder with many files makes these transient errors (timeouts, brief drops,
            // a server momentarily overwhelmed by the burst of concurrent requests) far more likely to hit
            // at least one of them. Retrying them here instead of failing outright avoids turning a
            // one-off hiccup into a permanent error the user has to manually retry.
            Result.retry()
        } else {
            Result.failure()
        }
    }

    private fun isTransientNetworkError(throwable: Throwable): Boolean =
        throwable is NoConnectionWithServerException ||
            throwable is NoNetworkConnectionException ||
            throwable is NetworkErrorException ||
            throwable is ServerResponseTimeoutException ||
            throwable is ServerConnectionTimeoutException ||
            throwable is ServerNotReachableException ||
            throwable is ServiceUnavailableException

    private fun composePendingIntentToRefreshCredentials(): PendingIntent {
        val updateCredentialsIntent =
            Intent(appContext, LoginActivity::class.java).apply {
                putExtra(EXTRA_ACCOUNT, account.name)
                putExtra(EXTRA_ACTION, ACTION_UPDATE_EXPIRED_TOKEN)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                addFlags(Intent.FLAG_FROM_BACKGROUND)
            }

        return PendingIntent.getActivity(
            appContext,
            System.currentTimeMillis().toInt(),
            updateCredentialsIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Shortcut straight to the system screen where the user can grant the "all files access"
     * permission needed to write into the shared external storage.
     */
    private fun composePendingIntentToGrantStoragePermission(): PendingIntent {
        val grantPermissionIntent =
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${appContext.packageName}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }

        return PendingIntent.getActivity(
            appContext,
            System.currentTimeMillis().toInt(),
            grantPermissionIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getClientForThisDownload(): OwnCloudClient = SingleSessionManager.getDefaultSingleton()
        .getClientFor(OwnCloudAccount(AccountUtils.getOwnCloudAccountByName(appContext, account.name), appContext), appContext)

    override fun onTransferProgress(
        progressRate: Long,
        totalTransferredSoFar: Long,
        totalToTransfer: Long,
        filePath: String
    ) {
        if (this.isStopped) {
            Timber.w("Cancelling remote operation. The worker is stopped by user or system")
            downloadRemoteFileOperation.cancel()
            downloadRemoteFileOperation.removeDatatransferProgressListener(this)
        }

        val percent: Int = if (totalToTransfer == -1L) -1 else (100.0 * totalTransferredSoFar.toDouble() / totalToTransfer.toDouble()).toInt()
        if (percent == lastPercent) return

        // Set current progress. Observers will listen.
        CoroutineScope(Dispatchers.IO).launch {
            val progress = workDataOf(WORKER_KEY_PROGRESS to percent)
            setProgress(progress)
        }

        lastPercent = percent
    }

    companion object {
        const val KEY_PARAM_ACCOUNT = "KEY_PARAM_ACCOUNT"
        const val KEY_PARAM_FILE_ID = "KEY_PARAM_FILE_ID"
        const val WORKER_KEY_PROGRESS = "KEY_PROGRESS"

        // Shared across every DownloadFileWorker instance in the process, so at most MAX_CONCURRENT_DOWNLOADS
        // of them are actually transferring at the same time regardless of how many WorkManager has started.
        private val downloadSemaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)
    }
}
