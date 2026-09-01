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
package com.owncloud.android.extensions

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

private const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY = "com.android.externalstorage.documents"
private const val PRIMARY_VOLUME_ID = "primary"

/**
 * Resolves a tree [Uri] returned by [android.content.Intent.ACTION_OPEN_DOCUMENT_TREE] into the
 * absolute [File] it points to on disk.
 *
 * This only works for folders picked from the built-in device/SD card storage picker (backed by
 * [EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY]), not for folders coming from other document providers
 * (cloud storage apps, etc.), since those do not expose a real filesystem path. Callers must already
 * hold broad filesystem access (e.g. MANAGE_EXTERNAL_STORAGE) to actually read/write the result.
 */
fun Uri.toLocalFileOrNull(): File? {
    if (authority != EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY || !DocumentsContract.isTreeUri(this)) return null

    val documentId = runCatching { DocumentsContract.getTreeDocumentId(this) }.getOrNull() ?: return null
    val volumeId = documentId.substringBefore(':', missingDelimiterValue = "")
    val relativePath = documentId.substringAfter(':', missingDelimiterValue = "")

    val volumeRoot = if (volumeId.equals(PRIMARY_VOLUME_ID, ignoreCase = true)) {
        Environment.getExternalStorageDirectory()
    } else if (volumeId.isNotEmpty()) {
        File("/storage/$volumeId")
    } else {
        return null
    }

    return if (relativePath.isEmpty()) volumeRoot else File(volumeRoot, relativePath)
}

/**
 * Builds the tree [Uri] that the built-in device/SD card storage picker (backed by
 * [EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY]) would return for this folder, so it can be passed as
 * [DocumentsContract.EXTRA_INITIAL_URI] to make [android.content.Intent.ACTION_OPEN_DOCUMENT_TREE]
 * open already at this location. Returns null if the folder is not under a known storage volume.
 */
fun File.toDocumentTreeUriOrNull(): Uri? {
    val externalRoot = Environment.getExternalStorageDirectory().absolutePath
    val documentId = when {
        absolutePath == externalRoot -> "$PRIMARY_VOLUME_ID:"
        absolutePath.startsWith(externalRoot + File.separator) ->
            "$PRIMARY_VOLUME_ID:" + absolutePath.removePrefix(externalRoot + File.separator)

        absolutePath.startsWith("/storage/") -> {
            val withoutPrefix = absolutePath.removePrefix("/storage/")
            val volumeId = withoutPrefix.substringBefore(File.separatorChar)
            val relativePath = withoutPrefix.substringAfter(File.separatorChar, missingDelimiterValue = "")
            if (relativePath.isEmpty()) "$volumeId:" else "$volumeId:$relativePath"
        }

        else -> return null
    }
    return runCatching { DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY, documentId) }.getOrNull()
}
