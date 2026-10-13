package com.itsluminous.samaroh.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Validates MIGRATION_14_15 (ADR-106) against the committed schema JSONs: existing parties
 * survive unlinked (`booking_id` null — nothing is linked retroactively); new rows can
 * carry the event link.
 */
@RunWith(RobolectricTestRunner::class)
class PartyBookingLinkMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            SamarohDatabase::class.java,
        )

    @Test
    fun `migration 14 to 15 adds nullable booking_id to parties`() {
        helper.createDatabase(TEST_DB, 14).use { db ->
            db.execSQL(
                "INSERT INTO parties (id, business_id, name, phone, notes, created_at, updated_at, deleted_at, business_related) " +
                    "VALUES ('p-1', 'b-1', 'Priya Sharma', NULL, NULL, 1725000000000, 1725000000000, NULL, 0)",
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 15, true, SamarohDatabase.MIGRATION_14_15)

        db.query("SELECT booking_id, business_related FROM parties WHERE id = 'p-1'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.isNull(0)).isTrue() // v0.22.0 customer parties stay as they are
            assertThat(cursor.getInt(1)).isEqualTo(0)
        }
        db.execSQL(
            "INSERT INTO parties (id, business_id, name, created_at, updated_at, business_related, booking_id) " +
                "VALUES ('p-2', 'b-1', 'Wedding - 20-Oct-2026 - Priya Sharma', 1725000000000, 1725000000000, 1, 'bk-1')",
        )
        db.query("SELECT booking_id FROM parties WHERE id = 'p-2'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("bk-1")
        }
    }

    private companion object {
        const val TEST_DB = "migration-test-party-link.db"
    }
}
