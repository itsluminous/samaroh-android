package com.itsluminous.samaroh.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Validates MIGRATION_4_5 (ADR-030) against the committed schema JSONs: existing booking
 * rows survive with a NULL colour (default themed look); the migrated schema matches
 * 5.json; new rows can persist a palette key.
 */
@RunWith(RobolectricTestRunner::class)
class BookingColorMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            SamarohDatabase::class.java,
        )

    @Test
    fun `migration 4 to 5 adds nullable color defaulting existing bookings to NULL`() {
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.execSQL(
                "INSERT INTO bookings (id, business_id, event_type, event_icon, customer_name, " +
                    "start_date, end_date, total_amount, security_deposit, status, created_by, created_at, updated_at) " +
                    "VALUES ('bk-1', 'b-1', 'wedding', '💒', 'Asha Devi', " +
                    "'2026-09-10', '2026-09-10', 20000000, 0, 'CONFIRMED', 'u-1', 1725000000000, 1725000000000)",
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, SamarohDatabase.MIGRATION_4_5)

        db.query("SELECT color FROM bookings WHERE id = 'bk-1'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.isNull(0)).isTrue()
        }
        // New rows can persist a palette key.
        db.execSQL(
            "INSERT INTO bookings (id, business_id, event_type, event_icon, customer_name, " +
                "start_date, end_date, total_amount, security_deposit, status, created_by, created_at, updated_at, color) " +
                "VALUES ('bk-2', 'b-1', 'birthday', '🎂', 'Ravi', " +
                "'2026-09-12', '2026-09-12', 0, 0, 'CONFIRMED', 'u-1', 1725000000000, 1725000000000, 'peacock')",
        )
        db.query("SELECT color FROM bookings WHERE id = 'bk-2'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("peacock")
        }
    }

    private companion object {
        const val TEST_DB = "booking-color-migration-test.db"
    }
}
