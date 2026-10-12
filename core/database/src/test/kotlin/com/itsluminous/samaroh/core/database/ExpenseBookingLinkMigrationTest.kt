package com.itsluminous.samaroh.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Validates MIGRATION_13_14 (ADR-101) against the committed schema JSONs: existing
 * expense rows survive unlinked (`booking_id` null); new rows can carry the event link.
 */
@RunWith(RobolectricTestRunner::class)
class ExpenseBookingLinkMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            SamarohDatabase::class.java,
        )

    @Test
    fun `migration 13 to 14 adds nullable booking_id to expenses`() {
        helper.createDatabase(TEST_DB, 13).use { db ->
            db.execSQL(
                "INSERT INTO expenses (id, business_id, party_id, direction, amountPaise, expense_date, notes, " +
                    "created_by, created_at, updated_at, deleted_at) " +
                    "VALUES ('e-1', 'b-1', 'p-1', 'paid', 50000, '2026-10-01', NULL, 'u-1', 1725000000000, 1725000000000, NULL)",
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 14, true, SamarohDatabase.MIGRATION_13_14)

        db.query("SELECT booking_id FROM expenses WHERE id = 'e-1'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.isNull(0)).isTrue()
        }
        db.execSQL(
            "INSERT INTO expenses (id, business_id, party_id, direction, amountPaise, expense_date, " +
                "created_by, created_at, updated_at, booking_id) " +
                "VALUES ('e-2', 'b-1', 'p-1', 'paid', 70000, '2026-10-02', 'u-1', 1725000000000, 1725000000000, 'bk-1')",
        )
        db.query("SELECT booking_id FROM expenses WHERE id = 'e-2'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("bk-1")
        }
    }

    private companion object {
        const val TEST_DB = "migration-test-expense-link.db"
    }
}
