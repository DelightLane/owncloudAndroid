/**
 * ownCloud Android client application
 *
 * Copyright (C) 2026 ownCloud GmbH.
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
package com.owncloud.android.data.providers

import android.os.Environment
import java.io.File

/**
 * Like [LegacyStorageProvider], stores files directly under a shared storage root instead of the
 * app-scoped storage directory, so the account/folder structure is browsable from outside the app.
 * Unlike it, the root itself can be any folder chosen by the user from Settings (including a folder
 * on an inserted SD card), and falls back to the primary external storage root when nothing has been
 * selected yet. Requires the MANAGE_EXTERNAL_STORAGE permission on API >= 30.
 */
class ConfigurableStorageProvider(
    rootFolderName: String,
    private val preferencesProvider: SharedPreferencesProvider,
) : LocalStorageProvider(rootFolderName) {

    override fun getPrimaryStorageDirectory(): File {
        val storedPath = preferencesProvider.getString(PREF_STORAGE_ROOT_PATH, null)
        if (storedPath != null) {
            val storedDirectory = File(storedPath)
            if (storedDirectory.exists() || storedDirectory.mkdirs()) {
                return storedDirectory
            }
        }
        return Environment.getExternalStorageDirectory()
    }

    companion object {
        const val PREF_STORAGE_ROOT_PATH = "prefs_storage_root_path"
    }
}
