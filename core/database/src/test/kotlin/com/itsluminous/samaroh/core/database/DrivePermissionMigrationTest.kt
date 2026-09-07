package com.itsluminous.samaroh.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Validates MIGRATION_8_9 (ADR-059) against the committed schema JSONs: existing
 * expense_attachments rows survive with the device-only permission flag unset (so the
 * repair pass picks them up); the migrated schema matches 9.json.
 */
@RunWith(RobolectricTestRunner::class)
class DrivePermissionMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            SamarohDatabase::class.java,
        )

    @Test
    fun `migration 8 to 9 adds drive_permission_ensured defaulting existing rows to pending`() {
        helper.createDatabase(TEST_DB, 8).use { db ->
            db.execSQL(
                "INSERT INTO expense_attachments (id, expense_id, business_id, drive_file_id, mime_type, file_name, created_at) " +
                    "VALUES ('att-1', 'e-1', 'b-1', 'drive-1', 'image/jpeg', 'bill.jpg', 1725000000000)",
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, SamarohDatabase.MIGRATION_8_9)

        db.query("SELECT drive_permission_ensured FROM expense_attachments WHERE id = 'att-1'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
        // New rows can persist the ensured flag.
        db.execSQL(
            "INSERT INTO expense_attachments " +
                "(id, expense_id, business_id, drive_file_id, mime_type, file_name, drive_permission_ensured, created_at) " +
                "VALUES ('att-2', 'e-1', 'b-1', 'drive-2', 'image/jpeg', 'bill2.jpg', 1, 1725000000000)",
        )
        db.query("SELECT drive_permission_ensured FROM expense_attachments WHERE id = 'att-2'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(1)
        }
    }

    private companion object {
        const val TEST_DB = "drive-permission-migration-test.db"
    }
}
