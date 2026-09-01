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

package com.owncloud.android.data.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.owncloud.android.data.ProviderMeta
import timber.log.Timber
import java.io.File

/**
 * Moving a downloaded file to a new storage location (SD card selection, legacy storage migration) used to
 * reset its last-modified time to "now", because the copy step behind the move did not preserve it. That
 * made every already-synced file look locally modified right after the move, which combined with an
 * unrelated remote etag difference on the same file to raise a false local-vs-remote conflict even though
 * the file was never touched.
 *
 * The move itself no longer resets the timestamp (see FileExt.moveRecursively), but files that already
 * went through the buggy move still carry the mismatch. Bring `lastSyncDateForData` up to the file's actual
 * last-modified time on disk so it stops looking locally modified.
 */
val MIGRATION_49_50 = object : Migration(49, 50) {
    override fun migrate(database: SupportSQLiteDatabase) {
        val query = "SELECT `id`, `storagePath`, `lastSyncDateForData` FROM ${ProviderMeta.ProviderTableMeta.FILES_TABLE_NAME} " +
            "WHERE `storagePath` IS NOT NULL AND `storagePath` != ''"
        val cursor = database.query(query)
        cursor.use {
            val idColumn = it.getColumnIndexOrThrow("id")
            val storagePathColumn = it.getColumnIndexOrThrow("storagePath")
            val lastSyncDateForDataColumn = it.getColumnIndexOrThrow("lastSyncDateForData")

            while (it.moveToNext()) {
                val id = it.getLong(idColumn)
                val storagePath = it.getString(storagePathColumn) ?: continue
                val lastSyncDateForData = if (it.isNull(lastSyncDateForDataColumn)) 0L else it.getLong(lastSyncDateForDataColumn)

                val localFile = File(storagePath)
                if (!localFile.exists()) continue

                val actualLastModified = localFile.lastModified()
                if (actualLastModified > lastSyncDateForData) {
                    database.execSQL(
                        "UPDATE ${ProviderMeta.ProviderTableMeta.FILES_TABLE_NAME} SET `lastSyncDateForData` = ? WHERE `id` = ?",
                        arrayOf(actualLastModified, id)
                    )
                    Timber.d("Fixed stale lastSyncDateForData for file $id, it no longer looks locally modified")
                }
            }
        }
    }
}
