package com.itsluminous.samaroh.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Validates MIGRATION_11_12 (ADR-077) against the committed schema JSONs: the three
 * NOTES tables appear with working defaults, pre-existing data is untouched, and the
 * migrated schema matches 12.json.
 */
@RunWith(RobolectricTestRunner::class)
class NotesMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            SamarohDatabase::class.java,
        )

    @Test
    fun `migration 11 to 12 creates the notes tables with defaults and keeps old data`() {
        helper.createDatabase(TEST_DB, 11).use { db ->
            db.execSQL(
                "INSERT INTO bookings (id, business_id, event_type, event_icon, customer_name, start_date, end_date, " +
                    "total_amount, security_deposit, status, created_by, created_at, updated_at) " +
                    "VALUES ('b-1', 'biz-1', 'wedding', 'x', 'c', '2026-09-10', '2026-09-10', 0, 0, 'confirmed', 'u-1', " +
                    "1725000000000, 1725000000000)",
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, SamarohDatabase.MIGRATION_11_12)

        // Pre-existing rows survive.
        db.query("SELECT id FROM bookings").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("b-1")
        }
        // A minimal insert exercises the column defaults (kind/checklist/pinned/status).
        db.execSQL(
            "INSERT INTO notes (id, business_id, created_by, created_at, updated_at) " +
                "VALUES ('n-1', 'biz-1', 'u-1', 1725000000000, 1725000000000)",
        )
        db.query("SELECT kind, checklist, pinned, status FROM notes WHERE id = 'n-1'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("note")
            assertThat(cursor.getString(1)).isEqualTo("[]")
            assertThat(cursor.getInt(2)).isEqualTo(0)
            assertThat(cursor.getString(3)).isEqualTo("active")
        }
        db.execSQL(
            "INSERT INTO note_tags (id, business_id, name, created_at, updated_at) " +
                "VALUES ('t-1', 'biz-1', 'urgent', 1725000000000, 1725000000000)",
        )
        db.execSQL(
            "INSERT INTO note_tag_links (note_id, tag_id, business_id, created_at, updated_at) " +
                "VALUES ('n-1', 't-1', 'biz-1', 1725000000000, 1725000000000)",
        )
        db.query("SELECT COUNT(*) FROM note_tag_links").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(1)
        }
    }

    private companion object {
        const val TEST_DB = "notes-migration-test.db"
    }
}
