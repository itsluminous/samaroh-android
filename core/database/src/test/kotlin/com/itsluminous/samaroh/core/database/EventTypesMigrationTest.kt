package com.itsluminous.samaroh.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Validates MIGRATION_5_6 (ADR-032) against the committed schema JSONs: the new
 * `event_types` table appears matching 6.json, is empty (seeding is server-side for
 * existing businesses / creation-time for new ones — never the migration), and rows
 * persist afterwards.
 */
@RunWith(RobolectricTestRunner::class)
class EventTypesMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            SamarohDatabase::class.java,
        )

    @Test
    fun `migration 5 to 6 creates an empty event_types table`() {
        helper.createDatabase(TEST_DB, 5).use { db ->
            db.execSQL(
                "INSERT INTO bookings (id, business_id, event_type, event_icon, customer_name, " +
                    "start_date, end_date, total_amount, security_deposit, status, created_by, created_at, updated_at) " +
                    "VALUES ('bk-1', 'b-1', 'wedding', '💒', 'Asha Devi', " +
                    "'2026-09-10', '2026-09-10', 20000000, 0, 'CONFIRMED', 'u-1', 1725000000000, 1725000000000)",
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, SamarohDatabase.MIGRATION_5_6)

        // NOT seeded locally: existing businesses receive rows from the server pull.
        db.query("SELECT COUNT(*) FROM event_types").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
        // Existing data survives untouched.
        db.query("SELECT event_type FROM bookings WHERE id = 'bk-1'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("wedding")
        }
        // New preset rows persist (nullable color, defaulted sort_order).
        db.execSQL(
            "INSERT INTO event_types (id, business_id, label, icon, color, sort_order, created_at, updated_at) " +
                "VALUES ('et-1', 'b-1', 'Wedding', '💒', 'tomato', 2, 1725000000000, 1725000000000)",
        )
        db.query("SELECT label, icon, color, sort_order FROM event_types WHERE id = 'et-1'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("Wedding")
            assertThat(cursor.getString(1)).isEqualTo("💒")
            assertThat(cursor.getString(2)).isEqualTo("tomato")
            assertThat(cursor.getInt(3)).isEqualTo(2)
        }
    }

    private companion object {
        const val TEST_DB = "event-types-migration-test.db"
    }
}
