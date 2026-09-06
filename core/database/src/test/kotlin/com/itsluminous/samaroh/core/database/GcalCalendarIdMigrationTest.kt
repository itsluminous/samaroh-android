package com.itsluminous.samaroh.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Validates MIGRATION_7_8 (ADR-048) against the committed schema JSONs: existing
 * business_settings rows survive with a NULL per-business calendar id; the migrated
 * schema matches 8.json.
 */
@RunWith(RobolectricTestRunner::class)
class GcalCalendarIdMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            SamarohDatabase::class.java,
        )

    @Test
    fun `migration 7 to 8 adds gcal_calendar_id defaulting existing rows to null`() {
        helper.createDatabase(TEST_DB, 7).use { db ->
            db.execSQL(
                "INSERT INTO business_settings (business_id, gcal_sync_enabled, backup_frequency, updated_at) " +
                    "VALUES ('b-1', 1, 'weekly', 1725000000000)",
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, SamarohDatabase.MIGRATION_7_8)

        db.query("SELECT gcal_calendar_id FROM business_settings WHERE business_id = 'b-1'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.isNull(0)).isTrue()
        }
        // New rows can persist the per-business calendar registry.
        db.execSQL(
            "INSERT INTO business_settings (business_id, gcal_sync_enabled, gcal_calendar_id, backup_frequency, updated_at) " +
                "VALUES ('b-2', 1, 'cal-abc', 'weekly', 1725000000000)",
        )
        db.query("SELECT gcal_calendar_id FROM business_settings WHERE business_id = 'b-2'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("cal-abc")
        }
    }

    private companion object {
        const val TEST_DB = "gcal-calendar-id-migration-test.db"
    }
}
