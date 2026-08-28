package com.itsluminous.samaroh.core.sync.engine

import com.itsluminous.samaroh.core.database.dao.BookingDao
import com.itsluminous.samaroh.core.database.dao.BookingPaymentDao
import com.itsluminous.samaroh.core.database.dao.BusinessDao
import com.itsluminous.samaroh.core.database.dao.BusinessMemberDao
import com.itsluminous.samaroh.core.database.dao.BusinessSettingsDao
import com.itsluminous.samaroh.core.database.dao.DateBlockDao
import com.itsluminous.samaroh.core.database.dao.EventTypeDao
import com.itsluminous.samaroh.core.database.dao.ExpenseAttachmentDao
import com.itsluminous.samaroh.core.database.dao.ExpenseDao
import com.itsluminous.samaroh.core.database.dao.GoogleAccountLinkDao
import com.itsluminous.samaroh.core.database.dao.InventoryTransactionDao
import com.itsluminous.samaroh.core.database.dao.MasterItemDao
import com.itsluminous.samaroh.core.database.dao.PartyDao
import com.itsluminous.samaroh.core.database.dao.PaymentReminderDao
import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.BookingPayment
import com.itsluminous.samaroh.core.model.Business
import com.itsluminous.samaroh.core.model.BusinessMember
import com.itsluminous.samaroh.core.model.BusinessSettings
import com.itsluminous.samaroh.core.model.DateBlock
import com.itsluminous.samaroh.core.model.EventType
import com.itsluminous.samaroh.core.model.Expense
import com.itsluminous.samaroh.core.model.ExpenseAttachment
import com.itsluminous.samaroh.core.model.GoogleAccountLink
import com.itsluminous.samaroh.core.model.InventoryTransaction
import com.itsluminous.samaroh.core.model.MasterItem
import com.itsluminous.samaroh.core.model.Party
import com.itsluminous.samaroh.core.model.PaymentReminder
import com.itsluminous.samaroh.core.model.ReminderKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies pulled rows (already in local JSON form — paise, normalized timestamps) to Room
 * via plain REPLACE upserts. Tombstoned rows (`deleted_at` set) propagate naturally as
 * soft deletes (§8 step 2).
 */
@Singleton
class LocalApplier
    @Inject
    constructor(
        private val businessDao: BusinessDao,
        private val businessMemberDao: BusinessMemberDao,
        private val businessSettingsDao: BusinessSettingsDao,
        private val googleAccountLinkDao: GoogleAccountLinkDao,
        private val bookingDao: BookingDao,
        private val eventTypeDao: EventTypeDao,
        private val dateBlockDao: DateBlockDao,
        private val bookingPaymentDao: BookingPaymentDao,
        private val paymentReminderDao: PaymentReminderDao,
        private val partyDao: PartyDao,
        private val expenseDao: ExpenseDao,
        private val expenseAttachmentDao: ExpenseAttachmentDao,
        private val masterItemDao: MasterItemDao,
        private val inventoryTransactionDao: InventoryTransactionDao,
    ) {
        private val json = Json { ignoreUnknownKeys = true }

        suspend fun apply(
            table: String,
            row: JsonObject,
        ) {
            when (table) {
                "businesses" -> businessDao.upsert(json.decodeFromJsonElement(Business.serializer(), row).toEntity())
                "business_members" ->
                    businessMemberDao.upsert(json.decodeFromJsonElement(BusinessMember.serializer(), row).toEntity())
                "business_settings" ->
                    businessSettingsDao.upsert(json.decodeFromJsonElement(BusinessSettings.serializer(), row).toEntity())
                "google_accounts" ->
                    googleAccountLinkDao.upsert(json.decodeFromJsonElement(GoogleAccountLink.serializer(), row).toEntity())
                "bookings" -> bookingDao.upsert(json.decodeFromJsonElement(Booking.serializer(), row).toEntity())
                "event_types" -> eventTypeDao.upsert(json.decodeFromJsonElement(EventType.serializer(), row).toEntity())
                "date_blocks" -> dateBlockDao.upsert(json.decodeFromJsonElement(DateBlock.serializer(), row).toEntity())
                "booking_payments" ->
                    bookingPaymentDao.upsert(json.decodeFromJsonElement(BookingPayment.serializer(), row).toEntity())
                "payment_reminders" -> {
                    val model = json.decodeFromJsonElement(PaymentReminder.serializer(), row)
                    // kind is Room-only state (ADR-020); preserve it across pulled updates.
                    val kind = paymentReminderDao.byId(model.id)?.kind ?: ReminderKind.PAYMENT
                    paymentReminderDao.upsert(model.toEntity(kind))
                }
                "parties" -> partyDao.upsert(json.decodeFromJsonElement(Party.serializer(), row).toEntity())
                "expenses" -> expenseDao.upsert(json.decodeFromJsonElement(Expense.serializer(), row).toEntity())
                "expense_attachments" -> {
                    val model = json.decodeFromJsonElement(ExpenseAttachment.serializer(), row)
                    // local_cache_path is Room-only state; preserve it across pulled updates.
                    val cachePath = expenseAttachmentDao.byId(model.id)?.localCachePath
                    expenseAttachmentDao.upsert(model.toEntity(cachePath))
                }
                "master_items" -> masterItemDao.upsert(json.decodeFromJsonElement(MasterItem.serializer(), row).toEntity())
                "inventory_transactions" ->
                    inventoryTransactionDao.upsert(json.decodeFromJsonElement(InventoryTransaction.serializer(), row).toEntity())
                else -> error("unknown synced table: $table")
            }
        }

        /** Human-readable row identifier for conflict notifications and the conflict log. */
        fun titleOf(row: JsonObject): String {
            for (key in listOf("customer_name", "name", "label", "display_name", "file_name")) {
                val value = row[key]
                if (value != null && value !is JsonNull) return value.jsonPrimitive.content
            }
            return row["id"]
                ?.takeIf { it !is JsonNull }
                ?.jsonPrimitive
                ?.content
                .orEmpty()
        }
    }
