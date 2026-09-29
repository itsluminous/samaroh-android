package com.itsluminous.samaroh.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Validates MIGRATION_12_13 (ADR-085) against the committed schema JSONs: the three
 * FILES tables appear with working defaults (device-only columns included), pre-existing
 * data is untouched, and the migrated schema matches 13.json.
 */
@RunWith(RobolectricTestRunner::class)
class FilesMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            SamarohDatabase::class.java,
        )

    @Test
    fun `migration 12 to 13 creates the files tables with defaults and keeps old data`() {
        helper.createDatabase(TEST_DB, 12).use { db ->
            db.execSQL(
                "INSERT INTO note_tags (id, business_id, name, created_at, updated_at) " +
                    "VALUES ('t-1', 'biz-1', 'urgent', 1725000000000, 1725000000000)",
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 13, true, SamarohDatabase.MIGRATION_12_13)

        db.query("SELECT id FROM note_tags").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("t-1")
        }
        db.execSQL(
            "INSERT INTO folders (id, business_id, name, created_by, created_at, updated_at) " +
                "VALUES ('f-1', 'biz-1', 'Contracts', 'u-1', 1725000000000, 1725000000000)",
        )
        db.query("SELECT restricted, parent_id FROM folders WHERE id = 'f-1'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(0)
            assertThat(cursor.isNull(1)).isTrue()
        }
        // A staged file: no Drive id yet, device-only columns defaulted.
        db.execSQL(
            "INSERT INTO files (id, business_id, folder_id, name, mime_type, size_bytes, created_by, created_at, updated_at) " +
                "VALUES ('file-1', 'biz-1', 'f-1', 'lease.pdf', 'application/pdf', 10, 'u-1', 1725000000000, 1725000000000)",
        )
        db.query("SELECT drive_file_id, local_cache_path, drive_permission_ensured FROM files WHERE id = 'file-1'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.isNull(0)).isTrue()
            assertThat(cursor.isNull(1)).isTrue()
            assertThat(cursor.getInt(2)).isEqualTo(0)
        }
        db.execSQL(
            "INSERT INTO folder_access (folder_id, member_id, business_id, created_at, updated_at) " +
                "VALUES ('f-1', 'm-1', 'biz-1', 1725000000000, 1725000000000)",
        )
        db.query("SELECT COUNT(*) FROM folder_access").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(1)
        }
    }

    private companion object {
        const val TEST_DB = "files-migration-test.db"
    }
}
