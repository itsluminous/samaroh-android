package com.itsluminous.samaroh.feature.menu.ui.settings

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.database.entity.BookingEntity
import com.itsluminous.samaroh.core.database.entity.BookingPaymentEntity
import com.itsluminous.samaroh.core.database.entity.BusinessMemberEntity
import com.itsluminous.samaroh.core.database.entity.DateBlockEntity
import com.itsluminous.samaroh.core.database.entity.ExpenseAttachmentEntity
import com.itsluminous.samaroh.core.database.entity.MasterItemEntity
import com.itsluminous.samaroh.core.database.entity.PartyEntity
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.testing.inMemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

/**
 * Mapper coverage (ADR-022): every synced entity type maps to a localized verb + noun +
 * human identifier — payload-first, Room-row fallback (queued deletes only carry a
 * tombstone), then a short id.
 */
@RunWith(RobolectricTestRunner::class)
class SyncEntryDisplayResolverTest {
    private lateinit var db: SamarohDatabase
    private lateinit var resolver: SyncEntryDisplayResolver

    private val now = Instant.parse("2026-08-27T06:00:00Z")
    private val later = Instant.parse("2026-08-27T07:00:00Z")

    @Before
    fun setUp() {
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
        resolver = SyncEntryDisplayResolver(db.syncDisplayDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun resolve(
        entityType: String,
        operation: OutboxOperation = OutboxOperation.UPSERT,
        payload: String,
        entityId: String = "11112222-3333-4444-5555-666677778888",
    ) = resolver.resolve(entityType, operation, payload, entityId)

    private fun upsertPayload(
        vararg fields: Pair<String, String?>,
        updated: Boolean = false,
    ): String {
        val created = "2026-08-27T06:00:00Z"
        val updatedAt = if (updated) "2026-08-27T07:00:00Z" else created
        val body =
            (fields.toList() + listOf("created_at" to created, "updated_at" to updatedAt))
                .filter { it.second != null }
                .joinToString(",") { (k, v) -> "\"$k\":\"$v\"" }
        return "{$body}"
    }

    private val tombstone = """{"id":"11112222-3333-4444-5555-666677778888","deleted_at":"2026-08-27T07:00:00Z"}"""

    // --- bookings ---

    @Test
    fun `booking add resolves customer name from payload`() =
        runTest {
            val display = resolve("bookings", payload = upsertPayload("customer_name" to "Sharma"))
            assertThat(display.verbRes).isEqualTo(R.string.settings_sync_verb_add)
            assertThat(display.nounRes).isEqualTo(R.string.settings_sync_entity_booking)
            assertThat(display.detail).isEqualTo(SyncEntryDetail.Text("Sharma"))
        }

    @Test
    fun `booking update detected when payload timestamps differ`() =
        runTest {
            val display = resolve("bookings", payload = upsertPayload("customer_name" to "Sharma", updated = true))
            assertThat(display.verbRes).isEqualTo(R.string.settings_sync_verb_update)
        }

    @Test
    fun `booking delete falls back to the tombstoned room row name`() =
        runTest {
            db.bookingDao().upsert(booking(id = "11112222-3333-4444-5555-666677778888", customerName = "Sharma", deletedAt = later))
            val display = resolve("bookings", operation = OutboxOperation.DELETE, payload = tombstone)
            assertThat(display.verbRes).isEqualTo(R.string.settings_sync_verb_delete)
            assertThat(display.detail).isEqualTo(SyncEntryDetail.Text("Sharma"))
        }

    @Test
    fun `booking with blank name falls back to start date`() =
        runTest {
            val display = resolve("bookings", payload = upsertPayload("customer_name" to "", "start_date" to "2027-01-28"))
            assertThat(display.detail).isEqualTo(SyncEntryDetail.Date(LocalDate.of(2027, 1, 28)))
        }

    // --- date blocks ---

    @Test
    fun `date block resolves start date from payload`() =
        runTest {
            val display = resolve("date_blocks", payload = upsertPayload("start_date" to "2027-03-01"))
            assertThat(display.nounRes).isEqualTo(R.string.settings_sync_entity_date_block)
            assertThat(display.detail).isEqualTo(SyncEntryDetail.Date(LocalDate.of(2027, 3, 1)))
        }

    @Test
    fun `date block delete falls back to the room row date`() =
        runTest {
            db.dateBlockDao().upsert(
                DateBlockEntity(
                    id = "11112222-3333-4444-5555-666677778888",
                    businessId = "b",
                    startDate = LocalDate.of(2027, 3, 1),
                    endDate = LocalDate.of(2027, 3, 2),
                    createdBy = "u",
                    createdAt = now,
                    updatedAt = later,
                    deletedAt = later,
                ),
            )
            val display = resolve("date_blocks", operation = OutboxOperation.DELETE, payload = tombstone)
            assertThat(display.detail).isEqualTo(SyncEntryDetail.Date(LocalDate.of(2027, 3, 1)))
        }

    // --- payments ---

    @Test
    fun `payment resolves amount plus customer via booking lookup`() =
        runTest {
            db.bookingDao().upsert(booking(id = "bk-1", customerName = "Sharma"))
            val payload =
                """{"booking_id":"bk-1","amountPaise":50000,"created_at":"2026-08-27T06:00:00Z","updated_at":"2026-08-27T06:00:00Z"}"""
            val display = resolve("booking_payments", payload = payload)
            assertThat(display.verbRes).isEqualTo(R.string.settings_sync_verb_add)
            assertThat(display.nounRes).isEqualTo(R.string.settings_sync_entity_payment)
            assertThat(display.detail).isEqualTo(SyncEntryDetail.AmountForName(50_000L, "Sharma"))
        }

    @Test
    fun `payment delete falls back to the room row amount and booking`() =
        runTest {
            db.bookingDao().upsert(booking(id = "bk-1", customerName = "Sharma"))
            db.bookingPaymentDao().upsert(
                BookingPaymentEntity(
                    id = "11112222-3333-4444-5555-666677778888",
                    bookingId = "bk-1",
                    businessId = "b",
                    amountPaise = 25_000L,
                    paidOn = LocalDate.of(2026, 8, 27),
                    createdBy = "u",
                    createdAt = now,
                    updatedAt = later,
                    deletedAt = later,
                ),
            )
            val display = resolve("booking_payments", operation = OutboxOperation.DELETE, payload = tombstone)
            assertThat(display.detail).isEqualTo(SyncEntryDetail.AmountForName(25_000L, "Sharma"))
        }

    @Test
    fun `payment without booking row shows plain amount`() =
        runTest {
            val payload =
                """{"booking_id":"missing","amountPaise":50000,"created_at":"2026-08-27T06:00:00Z","updated_at":"2026-08-27T06:00:00Z"}"""
            val display = resolve("booking_payments", payload = payload)
            assertThat(display.detail).isEqualTo(SyncEntryDetail.Amount(50_000L))
        }

    // --- reminders ---

    @Test
    fun `reminder resolves customer name via booking, date fallback`() =
        runTest {
            db.bookingDao().upsert(booking(id = "bk-1", customerName = "Sharma"))
            val withBooking = resolve("payment_reminders", payload = upsertPayload("booking_id" to "bk-1"))
            assertThat(withBooking.nounRes).isEqualTo(R.string.settings_sync_entity_reminder)
            assertThat(withBooking.detail).isEqualTo(SyncEntryDetail.Text("Sharma"))

            val withoutBooking = resolve("payment_reminders", payload = upsertPayload("remind_on" to "2027-02-14"))
            assertThat(withoutBooking.detail).isEqualTo(SyncEntryDetail.Date(LocalDate.of(2027, 2, 14)))
        }

    // --- parties / expenses / attachments ---

    @Test
    fun `party resolves its own name, room fallback on delete`() =
        runTest {
            val added = resolve("parties", payload = upsertPayload("name" to "Manjee"))
            assertThat(added.nounRes).isEqualTo(R.string.settings_sync_entity_party)
            assertThat(added.detail).isEqualTo(SyncEntryDetail.Text("Manjee"))

            db.partyDao().upsert(party(id = "11112222-3333-4444-5555-666677778888", name = "Manjee", deletedAt = later))
            val deleted = resolve("parties", operation = OutboxOperation.DELETE, payload = tombstone)
            assertThat(deleted.verbRes).isEqualTo(R.string.settings_sync_verb_delete)
            assertThat(deleted.detail).isEqualTo(SyncEntryDetail.Text("Manjee"))
        }

    @Test
    fun `expense resolves party name via lookup`() =
        runTest {
            db.partyDao().upsert(party(id = "p-1", name = "Manjee"))
            val display = resolve("expenses", payload = upsertPayload("party_id" to "p-1"))
            assertThat(display.nounRes).isEqualTo(R.string.settings_sync_entity_expense)
            assertThat(display.detail).isEqualTo(SyncEntryDetail.Text("Manjee"))
        }

    @Test
    fun `expense without party falls back to its amount`() =
        runTest {
            val payload =
                """{"party_id":"missing","amountPaise":75000,"created_at":"2026-08-27T06:00:00Z","updated_at":"2026-08-27T06:00:00Z"}"""
            val display = resolve("expenses", payload = payload)
            assertThat(display.detail).isEqualTo(SyncEntryDetail.Amount(75_000L))
        }

    @Test
    fun `attachment resolves file name, room fallback`() =
        runTest {
            val fromPayload = resolve("expense_attachments", payload = upsertPayload("file_name" to "receipt.jpg"))
            assertThat(fromPayload.nounRes).isEqualTo(R.string.settings_sync_entity_attachment)
            assertThat(fromPayload.detail).isEqualTo(SyncEntryDetail.Text("receipt.jpg"))

            db.expenseAttachmentDao().upsert(
                ExpenseAttachmentEntity(
                    id = "11112222-3333-4444-5555-666677778888",
                    expenseId = "e-1",
                    businessId = "b",
                    mimeType = "image/jpeg",
                    fileName = "bill.jpg",
                    createdAt = now,
                    deletedAt = later,
                ),
            )
            val deleted = resolve("expense_attachments", operation = OutboxOperation.DELETE, payload = tombstone)
            assertThat(deleted.detail).isEqualTo(SyncEntryDetail.Text("bill.jpg"))
        }

    // --- inventory ---

    @Test
    fun `master item resolves its name for add update and delete`() =
        runTest {
            val added = resolve("master_items", payload = upsertPayload("name" to "Bucket"))
            assertThat(added.verbRes).isEqualTo(R.string.settings_sync_verb_add)
            assertThat(added.nounRes).isEqualTo(R.string.settings_sync_entity_inventory_item)
            assertThat(added.detail).isEqualTo(SyncEntryDetail.Text("Bucket"))

            val updated = resolve("master_items", payload = upsertPayload("name" to "Spoon", updated = true))
            assertThat(updated.verbRes).isEqualTo(R.string.settings_sync_verb_update)
            assertThat(updated.detail).isEqualTo(SyncEntryDetail.Text("Spoon"))

            db.masterItemDao().upsert(masterItem(id = "11112222-3333-4444-5555-666677778888", name = "Bucket", deletedAt = later))
            val deleted = resolve("master_items", operation = OutboxOperation.DELETE, payload = tombstone)
            assertThat(deleted.verbRes).isEqualTo(R.string.settings_sync_verb_delete)
            assertThat(deleted.detail).isEqualTo(SyncEntryDetail.Text("Bucket"))
        }

    @Test
    fun `stock entry resolves the item name via lookup`() =
        runTest {
            db.masterItemDao().upsert(masterItem(id = "item-1", name = "Bucket"))
            val display = resolve("inventory_transactions", payload = upsertPayload("master_item_id" to "item-1"))
            assertThat(display.nounRes).isEqualTo(R.string.settings_sync_entity_stock_entry)
            assertThat(display.detail).isEqualTo(SyncEntryDetail.Text("Bucket"))
        }

    // --- business scope ---

    @Test
    fun `business and member resolve their names`() =
        runTest {
            val business = resolve("businesses", payload = upsertPayload("name" to "Four Season"))
            assertThat(business.nounRes).isEqualTo(R.string.settings_sync_entity_business)
            assertThat(business.detail).isEqualTo(SyncEntryDetail.Text("Four Season"))

            db.businessMemberDao().upsert(
                BusinessMemberEntity(
                    id = "11112222-3333-4444-5555-666677778888",
                    businessId = "b",
                    invitedEmail = "m@x.com",
                    displayName = "Ravi",
                    createdAt = now,
                    updatedAt = later,
                ),
            )
            val member = resolve("business_members", operation = OutboxOperation.UPSERT, payload = "{}")
            assertThat(member.nounRes).isEqualTo(R.string.settings_sync_entity_member)
            assertThat(member.detail).isEqualTo(SyncEntryDetail.Text("Ravi"))
        }

    @Test
    fun `business settings render as update with no identifier`() =
        runTest {
            // business_settings payloads carry no created_at — never a creation.
            val display = resolve("business_settings", payload = """{"business_id":"b","updated_at":"2026-08-27T06:00:00Z"}""")
            assertThat(display.verbRes).isEqualTo(R.string.settings_sync_verb_update)
            assertThat(display.nounRes).isEqualTo(R.string.settings_sync_entity_business_settings)
            assertThat(display.detail).isEqualTo(SyncEntryDetail.None)
        }

    @Test
    fun `google account resolves its email`() =
        runTest {
            val display = resolve("google_accounts", payload = """{"email":"owner@gmail.com","updated_at":"2026-08-27T06:00:00Z"}""")
            assertThat(display.nounRes).isEqualTo(R.string.settings_sync_entity_google_account)
            assertThat(display.detail).isEqualTo(SyncEntryDetail.Text("owner@gmail.com"))
        }

    // --- fallbacks ---

    @Test
    fun `unknown entity type falls back to record noun and short id`() =
        runTest {
            val display = resolve("mystery_table", payload = upsertPayload())
            assertThat(display.nounRes).isEqualTo(R.string.settings_sync_entity_record)
            assertThat(display.detail).isEqualTo(SyncEntryDetail.Text("11112222"))
        }

    @Test
    fun `malformed payload with no room row falls back to short id`() =
        runTest {
            val display = resolve("bookings", payload = "not-json")
            assertThat(display.verbRes).isEqualTo(R.string.settings_sync_verb_update)
            assertThat(display.detail).isEqualTo(SyncEntryDetail.Text("11112222"))
        }

    // --- fixtures ---

    private fun booking(
        id: String,
        customerName: String,
        deletedAt: Instant? = null,
    ) = BookingEntity(
        id = id,
        businessId = "b",
        eventType = "wedding",
        eventIcon = "x",
        customerName = customerName,
        startDate = LocalDate.of(2027, 1, 28),
        endDate = LocalDate.of(2027, 1, 28),
        createdBy = "u",
        createdAt = now,
        updatedAt = deletedAt ?: now,
        deletedAt = deletedAt,
    )

    private fun party(
        id: String,
        name: String,
        deletedAt: Instant? = null,
    ) = PartyEntity(id = id, businessId = "b", name = name, createdAt = now, updatedAt = deletedAt ?: now, deletedAt = deletedAt)

    private fun masterItem(
        id: String,
        name: String,
        deletedAt: Instant? = null,
    ) = MasterItemEntity(
        id = id,
        businessId = "b",
        name = name,
        unit = "pcs",
        imagePath = null,
        driveImageId = null,
        createdAt = now,
        updatedAt = deletedAt ?: now,
        deletedAt = deletedAt,
    )
}
