package com.itsluminous.samaroh.core.google.backup

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.testing.inMemoryDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Disaster-recovery regression guard (spec §4.4): every table in the live Room schema
 * must be either exported by [BackupExporter] or on an explicit, justified exclusion
 * list. The schema is read REFLECTIVELY off the compiled database (sqlite_master), so
 * adding a new synced Room entity without teaching the exporter about it fails this
 * test — the backup can never silently fall behind the schema again.
 */
@RunWith(RobolectricTestRunner::class)
class BackupExporterSchemaGuardTest {
    private lateinit var db: com.itsluminous.samaroh.core.database.SamarohDatabase

    /** SQLite/Room bookkeeping tables — not app schema. */
    private val infrastructureTables = setOf("android_metadata", "sqlite_sequence", "room_master_table")

    @Before
    fun setUp() {
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun schemaTables(): Set<String> {
        val tables = mutableSetOf<String>()
        db.query(SimpleSQLiteQuery("SELECT name FROM sqlite_master WHERE type = 'table'")).use { cursor ->
            while (cursor.moveToNext()) tables += cursor.getString(0)
        }
        return tables - infrastructureTables
    }

    @Test
    fun `every Room table is exported or explicitly excluded`() {
        val covered =
            BackupExporter.EXPORTED_TABLES.toSet() +
                BackupExporter.EXCLUDED_PER_USER_TABLES +
                BackupExporter.EXCLUDED_LOCAL_TABLES
        val uncovered = schemaTables() - covered
        assertThat(uncovered).isEmpty() // new entity? add it to the exporter (or justify an exclusion) + docs/backup-format.md
    }

    @Test
    fun `exporter and exclusion lists carry no stale or duplicated tables`() {
        val schema = schemaTables()
        val declared =
            BackupExporter.EXPORTED_TABLES +
                BackupExporter.EXCLUDED_PER_USER_TABLES +
                BackupExporter.EXCLUDED_LOCAL_TABLES
        // Every declared name must still exist in the schema (catches renames/drops)…
        assertThat(schema).containsAtLeastElementsIn(declared)
        // …and no table may appear on two lists (or twice on one).
        assertThat(declared).containsNoDuplicates()
    }
}
