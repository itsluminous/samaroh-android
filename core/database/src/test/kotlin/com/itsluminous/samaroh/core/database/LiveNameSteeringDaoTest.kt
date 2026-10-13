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
    fun `liveByExactName matches live parties case- and whitespace-insensitively, earliest first`() =
        runTest {
            val dao = db.partyDao()
            dao.upsert(party("p-dead", "Priya Sharma", deletedAt = t1))
            dao.upsert(party("p-newer", "PRIYA SHARMA").copy(createdAt = t2, updatedAt = t2))
            dao.upsert(party("p-older", " priya sharma ").copy(createdAt = t1, updatedAt = t1))
            dao.upsert(party("p-other", "Priya Sharmaa"))

            // ADR-101 §2: tombstones never match; several live matches → earliest created_at.
            assertThat(dao.liveByExactName(TEST_BUSINESS_ID, "  Priya SHARMA")?.id).isEqualTo("p-older")
            assertThat(dao.liveByExactName(TEST_BUSINESS_ID, "Nobody")).isNull()
            assertThat(dao.liveByExactName("other-business", "Priya Sharma")).isNull()
        }

    @Test
    fun `liveByBooking returns the live event party linked to a booking, earliest first, business-scoped`() =
        runTest {
            val dao = db.partyDao()
            dao.upsert(party("p-dead", "Wedding - 20-Oct-2026 - A", deletedAt = t1).copy(bookingId = "bk-1"))
            dao.upsert(party("p-newer", "Renamed by owner").copy(bookingId = "bk-1", createdAt = t2, updatedAt = t2))
            dao.upsert(party("p-older", "Wedding - 20-Oct-2026 - A").copy(bookingId = "bk-1", createdAt = t1, updatedAt = t1))
            dao.upsert(party("p-other-booking", "Birthday - 01-Jan-2026 - B").copy(bookingId = "bk-2"))
            dao.upsert(party("p-plain", "Ram Sweets"))

            // ADR-106 §2.2 step 1: by link, whatever the name; tombstones never; earliest created_at.
            assertThat(dao.liveByBooking(TEST_BUSINESS_ID, "bk-1")?.id).isEqualTo("p-older")
            assertThat(dao.liveByBooking(TEST_BUSINESS_ID, "bk-2")?.id).isEqualTo("p-other-booking")
            assertThat(dao.liveByBooking(TEST_BUSINESS_ID, "bk-none")).isNull()
            assertThat(dao.liveByBooking("other-business", "bk-1")).isNull()
        }

    @Test
    fun `liveForBooking lists live linked expenses newest first by expense_date then created_at`() =
        runTest {
            db.partyDao().upsert(party("p-1", "Wedding - 20-Oct-2026 - A").copy(bookingId = "bk-1"))
            val dao = db.expenseDao()

            fun expense(
                id: String,
                date: String,
                createdAt: Instant,
                bookingId: String? = "bk-1",
                deletedAt: Instant? = null,
            ) = com.itsluminous.samaroh.core.database.entity.ExpenseEntity(
                id = id,
                businessId = TEST_BUSINESS_ID,
                partyId = "p-1",
                amountPaise = 100_00,
                expenseDate = java.time.LocalDate.parse(date),
                createdBy = "u-1",
                createdAt = createdAt,
                updatedAt = createdAt,
                deletedAt = deletedAt,
                bookingId = bookingId,
            )
            dao.upsert(expense("e-old", "2026-10-01", t0))
            dao.upsert(expense("e-same-early", "2026-10-05", t0))
            dao.upsert(expense("e-same-late", "2026-10-05", t1))
            dao.upsert(expense("e-dead", "2026-10-09", t2, deletedAt = t2))
            dao.upsert(expense("e-other", "2026-10-09", t2, bookingId = "bk-2"))
            dao.upsert(expense("e-plain", "2026-10-09", t2, bookingId = null)) // against the event party, no link → not listed

            assertThat(dao.liveForBooking("bk-1").first().map { it.id })
                .containsExactly("e-same-late", "e-same-early", "e-old")
                .inOrder()
            assertThat(dao.liveForBooking("bk-none").first()).isEmpty()
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
