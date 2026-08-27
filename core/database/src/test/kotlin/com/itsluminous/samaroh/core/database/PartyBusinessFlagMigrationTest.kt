package com.itsluminous.samaroh.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Validates MIGRATION_3_4 (ADR-027) against the committed schema JSONs: existing party
 * rows survive and default to business-related; the migrated schema matches 4.json.
 */
@RunWith(RobolectricTestRunner::class)
class PartyBusinessFlagMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            SamarohDatabase::class.java,
        )

    @Test
    fun `migration 3 to 4 adds business_related defaulting existing parties to 1`() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL(
                "INSERT INTO parties (id, business_id, name, phone, notes, created_at, updated_at, deleted_at) " +
                    "VALUES ('p-1', 'b-1', 'Caterer', NULL, NULL, 1725000000000, 1725000000000, NULL)",
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, SamarohDatabase.MIGRATION_3_4)

        db.query("SELECT business_related FROM parties WHERE id = 'p-1'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(1)
        }
        // New rows can persist the personal flag.
        db.execSQL(
            "INSERT INTO parties (id, business_id, name, created_at, updated_at, business_related) " +
                "VALUES ('p-2', 'b-1', 'Family friend', 1725000000000, 1725000000000, 0)",
        )
        db.query("SELECT business_related FROM parties WHERE id = 'p-2'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
