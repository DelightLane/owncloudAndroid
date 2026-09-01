/**
 * ownCloud Android client application
 *
 * @author David Crespo Ríos
 * @author Aitor Ballesteros Pavón
 * @author Jorge Aguado Recio
 *
 * Copyright (C) 2025 ownCloud GmbH.
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

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentTransaction
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.owncloud.android.R
import com.owncloud.android.domain.utils.Event
import com.owncloud.android.extensions.showAlertDialog
import com.owncloud.android.extensions.showMessageInSnackbar
import com.owncloud.android.extensions.toDocumentTreeUriOrNull
import com.owncloud.android.extensions.toLocalFileOrNull
import com.owncloud.android.ui.dialog.LoadingDialog
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber
import java.io.File

class SettingsAdvancedFragment : PreferenceFragmentCompat() {

    // ViewModel
    private val advancedViewModel by viewModel<SettingsAdvancedViewModel>()

    private var prefShowHiddenFiles: SwitchPreferenceCompat? = null
    private var prefShowDisabledSpaces: SwitchPreferenceCompat? = null
    private var prefRemoveLocalFiles: ListPreference? = null
    private var prefStorageLocation: Preference? = null

    private val selectStorageLocationLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val treeUri = result.data?.data ?: return@registerForActivityResult
            val selectedFolder = treeUri.toLocalFileOrNull()
            if (selectedFolder == null || !(selectedFolder.exists() || selectedFolder.mkdirs()) || !selectedFolder.canWrite()) {
                showMessageInSnackbar(getString(R.string.prefs_storage_location_invalid_folder))
                return@registerForActivityResult
            }
            showLoadingDialog()
            advancedViewModel.changeStorageLocation(selectedFolder)
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_advanced, rootKey)

        prefShowHiddenFiles = findPreference(PREF_SHOW_HIDDEN_FILES)
        prefRemoveLocalFiles = findPreference<ListPreference>(PREFERENCE_REMOVE_LOCAL_FILES)?.apply {
            entries = listOf(
                getString(R.string.prefs_delete_local_files_entries_never),
                getString(R.string.prefs_delete_local_files_entries_1hour),
                getString(R.string.prefs_delete_local_files_entries_12hours),
                getString(R.string.prefs_delete_local_files_entries_1day),
                getString(R.string.prefs_delete_local_files_entries_30days)
            ).toTypedArray()
            entryValues = listOf(
                RemoveLocalFiles.NEVER.name,
                RemoveLocalFiles.ONE_HOUR.name,
                RemoveLocalFiles.TWELVE_HOURS.name,
                RemoveLocalFiles.ONE_DAY.name,
                RemoveLocalFiles.THIRTY_DAYS.name,
            ).toTypedArray()
            summary = getString(R.string.prefs_delete_local_files_summary, this.entry)
        }
        prefShowDisabledSpaces = findPreference(PREF_SHOW_DISABLED_SPACES)
        prefStorageLocation = findPreference(PREF_STORAGE_LOCATION)
        initPreferenceListeners()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefShowHiddenFiles?.isChecked = advancedViewModel.isHiddenFilesShown()
        prefStorageLocation?.summary = advancedViewModel.getCurrentStorageRootPath()

        advancedViewModel.storageLocationChanged.observe(viewLifecycleOwner, Event.EventObserver { success ->
            dismissLoadingDialog()
            if (success) {
                prefStorageLocation?.summary = advancedViewModel.getCurrentStorageRootPath()
                showMessageInSnackbar(getString(R.string.prefs_storage_location_changed))
            } else {
                showMessageInSnackbar(getString(R.string.prefs_storage_location_change_failed))
            }
        })
    }

    private fun initPreferenceListeners() {
        prefShowHiddenFiles?.setOnPreferenceChangeListener { _: Preference?, newValue: Any ->
            advancedViewModel.setShowHiddenFiles(newValue as Boolean)
            true
        }

        prefShowDisabledSpaces?.setOnPreferenceChangeListener { _: Preference?, newValue: Any ->
            advancedViewModel.setShowDisabledSpaces(newValue as Boolean)
            true
        }

        prefRemoveLocalFiles?.setOnPreferenceChangeListener { preference: Preference?, newValue: Any ->
            val index = (preference as ListPreference).findIndexOfValue(newValue as String)
            preference.summary = getString(R.string.prefs_delete_local_files_summary, preference.entries[index])
            advancedViewModel.scheduleDeleteLocalFiles(newValue)
            true
        }

        prefStorageLocation?.setOnPreferenceClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                showAlertDialog(
                    title = getString(R.string.common_important),
                    message = getString(R.string.prefs_storage_location_permission_required, getString(R.string.app_name)),
                    positiveButtonListener = { _, _ -> openManageAllFilesAccessSettings() },
                )
            } else {
                launchStorageLocationPicker()
            }
            true
        }
    }

    private fun launchStorageLocationPicker() {
        val currentRoot = File(advancedViewModel.getCurrentStorageRootPath())
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            currentRoot.toDocumentTreeUriOrNull()?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }
        selectStorageLocationLauncher.launch(intent)
    }

    private fun openManageAllFilesAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${requireContext().packageName}")))
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION not available, falling back to generic screen")
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e: ActivityNotFoundException) {
                Timber.w(e, "ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION not available either")
            }
        }
    }

    private fun showLoadingDialog() {
        val fragmentManager = requireActivity().supportFragmentManager
        if (fragmentManager.findFragmentByTag(DIALOG_STORAGE_LOCATION_WAIT_TAG) != null) return
        val loading = LoadingDialog.newInstance(R.string.wait_a_moment, false)
        val fragmentTransaction: FragmentTransaction = fragmentManager.beginTransaction()
        loading.show(fragmentTransaction, DIALOG_STORAGE_LOCATION_WAIT_TAG)
    }

    private fun dismissLoadingDialog() {
        val fragmentManager = requireActivity().supportFragmentManager
        val waitDialogFragment = fragmentManager.findFragmentByTag(DIALOG_STORAGE_LOCATION_WAIT_TAG) as? LoadingDialog
        waitDialogFragment?.dismiss()
    }

    companion object {
        const val PREF_SHOW_HIDDEN_FILES = "show_hidden_files"
        const val PREF_SHOW_DISABLED_SPACES = "show_disabled_spaces"
        const val PREF_STORAGE_LOCATION = "storage_location"
        private const val DIALOG_STORAGE_LOCATION_WAIT_TAG = "DIALOG_STORAGE_LOCATION_WAIT_TAG"
    }
}
