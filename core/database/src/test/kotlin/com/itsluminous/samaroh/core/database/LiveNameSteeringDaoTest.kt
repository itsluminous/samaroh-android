package com.itsluminous.samaroh.core.database

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.database.entity.EventTypeEntity
import com.itsluminous.samaroh.core.database.entity.MasterItemEntity
import com.itsluminous.samaroh.core.database.entity.NoteTagEntity
import com.itsluminous.samaroh.core.database.entity.PartyEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * Pins the tombstone/name contract shared with migration 008 (`samaroh-shared`,
 * `uq_parties_biz_name` / `uq_master_items_biz_name`): server-side, a name is unique per
 * business over LIVE rows only, and clients never resurrect a tombstoned id — a deleted
 * name is re-created as a brand-new row. For that to work every duplicate-steering
 * source on the device (the queries the add/edit dialogs dedup against) must exclude
 * tombstones, otherwise the dialog would "steer" the user to a dead row and the
 * re-create could never happen. Also asserts that a tombstoned twin and its live
 * re-creation coexist in Room (no local uniqueness on name).
 */
@RunWith(RobolectricTestRunner::class)
class LiveNameSteeringDaoTest {
    private lateinit var db: SamarohDatabase
    private val t0 = Instant.parse("2026-09-17T06:31:58Z")
    private val t1 = Instant.parse("2026-09-17T11:58:37Z")
    private val t2 = Instant.parse("2026-09-25T10:00:00Z")

    @Before
    fun setUp() {
        db = testDatabase()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun party(
        id: String,
        name: String,
        deletedAt: Instant? = null,
    ) = PartyEntity(id = id, businessId = TEST_BUSINESS_ID, name = name, createdAt = t0, updatedAt = t0, deletedAt = deletedAt)

    private fun item(
        id: String,
        name: String,
        deletedAt: Instant? = null,
    ) = MasterItemEntity(
        id = id,
        businessId = TEST_BUSINESS_ID,
        name = name,
        unit = "pcs",
        createdAt = t0,
        updatedAt = t0,
        deletedAt = deletedAt,
    )

    private fun tag(
        id: String,
        name: String,
        deletedAt: Instant? = null,
    ) = NoteTagEntity(id = id, businessId = TEST_BUSINESS_ID, name = name, createdAt = t0, updatedAt = t0, deletedAt = deletedAt)

    private fun eventType(
        id: String,
        label: String,
        deletedAt: Instant? = null,
    ) = EventTypeEntity(
        id = id,
        businessId = TEST_BUSINESS_ID,
        label = label,
        icon = "\uD83D\uDC92",
        createdAt = t0,
        updatedAt = t0,
        deletedAt = deletedAt,
    )

    @Test
    fun `parties steering source hides the tombstoned twin and shows only the live re-creation`() =
        runTest {
            val dao = db.partyDao()
            dao.upsert(party("p-old", "bhuneshwar singh"))
            dao.tombstone("p-old", t1)
            // Re-create with a NEW id (clients never resurrect the old id).
            dao.upsert(party("p-new", "bhuneshwar singh").copy(createdAt = t2, updatedAt = t2))

            val steering = dao.partiesWithBalance(TEST_BUSINESS_ID).first().map { it.party.id }
            assertThat(steering).containsExactly("p-new")
            assertThat(dao.searchByName(TEST_BUSINESS_ID, "bhuneshwar").map { it.id }).containsExactly("p-new")
        }

    @Test
    fun `parties with only a tombstoned twin steer to nothing so the dialog creates a new row`() =
        runTest {
            val dao = db.partyDao()
            dao.upsert(party("p-old", "Ganga Bhog"))
            dao.tombstone("p-old", t1)

            assertThat(dao.partiesWithBalance(TEST_BUSINESS_ID).first()).isEmpty()
            assertThat(dao.searchByName(TEST_BUSINESS_ID, "Ganga")).isEmpty()
        }

    @Test
    fun `master items steering source hides the tombstoned twin and shows only the live re-creation`() =
        runTest {
            val dao = db.masterItemDao()
            dao.upsert(item("i-old", "Plastic Chair"))
            dao.tombstone("i-old", t1)
            dao.upsert(item("i-new", "plastic chair").copy(createdAt = t2, updatedAt = t2))

            assertThat(dao.itemsForBusiness(TEST_BUSINESS_ID).first().map { it.id }).containsExactly("i-new")
            assertThat(dao.searchByName(TEST_BUSINESS_ID, "chair").map { it.id }).containsExactly("i-new")
        }

    @Test
    fun `note tags steering source hides tombstoned tags`() =
        runTest {
            val dao = db.noteTagDao()
            dao.upsert(tag("t-old", "Urgent", deletedAt = t1))
            dao.upsert(tag("t-new", "urgent").copy(createdAt = t2, updatedAt = t2))

            assertThat(dao.tagsForBusiness(TEST_BUSINESS_ID).first().map { it.id }).containsExactly("t-new")
        }

    @Test
    fun `event type label uniqueness check ignores tombstoned presets`() =
        runTest {
            val dao = db.eventTypeDao()
            dao.upsert(eventType("e-old", "Tilak", deletedAt = t1))

            // Only a tombstoned twin exists: the label is free to re-create.
            assertThat(dao.countLabelUses(TEST_BUSINESS_ID, "Tilak", excludingId = "")).isEqualTo(0)

            dao.upsert(eventType("e-new", "tilak"))
            // A LIVE twin (case-insensitive) is a duplicate...
            assertThat(dao.countLabelUses(TEST_BUSINESS_ID, "Tilak", excludingId = "")).isEqualTo(1)
            // ...unless it is the row being edited.
            assertThat(dao.countLabelUses(TEST_BUSINESS_ID, "Tilak", excludingId = "e-new")).isEqualTo(0)
        }
}
