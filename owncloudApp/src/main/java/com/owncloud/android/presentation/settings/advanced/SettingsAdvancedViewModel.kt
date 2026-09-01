/**
 * ownCloud Android client application
 *
 * @author David Crespo Ríos
 * @author Aitor Ballesteros Pavón
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

package com.owncloud.android.presentation.settings.advanced

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.owncloud.android.data.providers.ConfigurableStorageProvider
import com.owncloud.android.data.providers.LocalStorageProvider
import com.owncloud.android.data.providers.SharedPreferencesProvider
import com.owncloud.android.domain.files.usecases.UpdateAlreadyDownloadedFilesPathUseCase
import com.owncloud.android.domain.transfers.usecases.GetAllTransfersUseCase
import com.owncloud.android.domain.transfers.usecases.UpdatePendingUploadsPathUseCase
import com.owncloud.android.domain.utils.Event
import com.owncloud.android.presentation.settings.advanced.SettingsAdvancedFragment.Companion.PREF_SHOW_DISABLED_SPACES
import com.owncloud.android.presentation.settings.advanced.SettingsAdvancedFragment.Companion.PREF_SHOW_HIDDEN_FILES
import com.owncloud.android.providers.AccountProvider
import com.owncloud.android.providers.CoroutinesDispatcherProvider
import com.owncloud.android.providers.WorkManagerProvider
import com.owncloud.android.workers.RemoveLocallyFilesWithLastUsageOlderThanGivenTimeWorker.Companion.DELETE_FILES_OLDER_GIVEN_TIME_WORKER
import kotlinx.coroutines.launch
import java.io.File

class SettingsAdvancedViewModel(
    private val preferencesProvider: SharedPreferencesProvider,
    private val workManagerProvider: WorkManagerProvider,
    private val localStorageProvider: LocalStorageProvider,
    private val accountProvider: AccountProvider,
    private val updatePendingUploadsPathUseCase: UpdatePendingUploadsPathUseCase,
    private val updateAlreadyDownloadedFilesPathUseCase: UpdateAlreadyDownloadedFilesPathUseCase,
    private val getAllTransfersUseCase: GetAllTransfersUseCase,
    private val coroutinesDispatcherProvider: CoroutinesDispatcherProvider,
) : ViewModel() {

    private val _storageLocationChanged = MutableLiveData<Event<Boolean>>()
    val storageLocationChanged: LiveData<Event<Boolean>> = _storageLocationChanged

    fun isHiddenFilesShown(): Boolean =
        preferencesProvider.getBoolean(PREF_SHOW_HIDDEN_FILES, false)

    fun setShowHiddenFiles(hide: Boolean) {
        preferencesProvider.putBoolean(PREF_SHOW_HIDDEN_FILES, hide)
    }

    fun setShowDisabledSpaces(showDisabledSpaces: Boolean) {
        preferencesProvider.putBoolean(PREF_SHOW_DISABLED_SPACES, showDisabledSpaces)
    }

    fun scheduleDeleteLocalFiles(newValue: String) {
        workManagerProvider.cancelAllWorkByTag(DELETE_FILES_OLDER_GIVEN_TIME_WORKER)
        if (newValue != RemoveLocalFiles.NEVER.name) {
            workManagerProvider.enqueueRemoveLocallyFilesWithLastUsageOlderThanGivenTimeWorker()
        }
    }

    /**
     * Absolute path where downloaded files are currently stored, e.g. /storage/emulated/0/owncloud.
     */
    fun getCurrentStorageRootPath(): String = localStorageProvider.getRootFolderPath()

    /**
     * Moves every account's local data from the current storage root into [newRootDirectory] and points
     * future downloads to it. Reports the outcome through [storageLocationChanged]. On failure, the
     * previously selected location is restored so the app keeps working with the data that is still there.
     */
    fun changeStorageLocation(newRootDirectory: File) {
        viewModelScope.launch(coroutinesDispatcherProvider.io) {
            val oldRootFolderPath = localStorageProvider.getRootFolderPath()
            val previousStoredPath = preferencesProvider.getString(ConfigurableStorageProvider.PREF_STORAGE_ROOT_PATH, null)

            preferencesProvider.putString(ConfigurableStorageProvider.PREF_STORAGE_ROOT_PATH, newRootDirectory.absolutePath)
            val newRootFolderPath = localStorageProvider.getRootFolderPath()

            val success = if (oldRootFolderPath == newRootFolderPath) {
                true
            } else {
                runCatching {
                    localStorageProvider.moveDataFromPreviousStorageLocation(oldRootFolderPath)
                    updatePendingUploadsPathUseCase(
                        UpdatePendingUploadsPathUseCase.Params(oldDirectory = oldRootFolderPath, newDirectory = newRootFolderPath)
                    )
                    updateAlreadyDownloadedFilesPathUseCase(
                        UpdateAlreadyDownloadedFilesPathUseCase.Params(oldDirectory = oldRootFolderPath, newDirectory = newRootFolderPath)
                    )
                    val uploads = getAllTransfersUseCase(Unit)
                    val accountsNames = accountProvider.getLoggedAccounts().map { it.name }
                    localStorageProvider.clearUnrelatedTemporalFiles(uploads, accountsNames)
                }.isSuccess
            }

            if (!success) {
                if (previousStoredPath != null) {
                    preferencesProvider.putString(ConfigurableStorageProvider.PREF_STORAGE_ROOT_PATH, previousStoredPath)
                } else {
                    preferencesProvider.removePreference(ConfigurableStorageProvider.PREF_STORAGE_ROOT_PATH)
                }
            }

            _storageLocationChanged.postValue(Event(success))
        }
    }
}
