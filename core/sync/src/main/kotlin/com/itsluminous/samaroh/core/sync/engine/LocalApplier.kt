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
 *
 * [apply] reports whether the row actually CHANGED Room (ADR-051): the keyset cursor is
 * ms-truncated on persistence while Postgres compares at µs, so every run re-serves each
 * table's boundary row. Re-applying an identical row must not count as "applied" — it fed
 * the remote-change listeners (spurious calendar pushes every sync run) and the post-sync
 * hooks. An incoming entity equal to the stored one skips the write and returns false.
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

        /** @return true when the row changed Room; false for an identical no-op re-apply. */
        suspend fun apply(
            table: String,
            row: JsonObject,
        ): Boolean =
            when (table) {
                "businesses" ->
                    upsertIfChanged(
                        json.decodeFromJsonElement(Business.serializer(), row).toEntity(),
                        businessDao::byId,
                        businessDao::upsert,
                    ) { it.id }
                "business_members" ->
                    upsertIfChanged(
                        json.decodeFromJsonElement(BusinessMember.serializer(), row).toEntity(),
                        businessMemberDao::byId,
                        businessMemberDao::upsert,
                    ) { it.id }
                "business_settings" ->
                    upsertIfChanged(
                        json.decodeFromJsonElement(BusinessSettings.serializer(), row).toEntity(),
                        businessSettingsDao::byId,
                        businessSettingsDao::upsert,
                    ) { it.businessId }
                "google_accounts" ->
                    upsertIfChanged(
                        json.decodeFromJsonElement(GoogleAccountLink.serializer(), row).toEntity(),
                        googleAccountLinkDao::byId,
                        googleAccountLinkDao::upsert,
                    ) { it.userId }
                "bookings" ->
                    upsertIfChanged(
                        json.decodeFromJsonElement(Booking.serializer(), row).toEntity(),
                        bookingDao::byId,
                        bookingDao::upsert,
                    ) { it.id }
                "event_types" ->
                    upsertIfChanged(
                        json.decodeFromJsonElement(EventType.serializer(), row).toEntity(),
                        eventTypeDao::byId,
                        eventTypeDao::upsert,
                    ) { it.id }
                "date_blocks" ->
                    upsertIfChanged(
                        json.decodeFromJsonElement(DateBlock.serializer(), row).toEntity(),
                        dateBlockDao::byId,
                        dateBlockDao::upsert,
                    ) { it.id }
                "booking_payments" ->
                    upsertIfChanged(
                        json.decodeFromJsonElement(BookingPayment.serializer(), row).toEntity(),
                        bookingPaymentDao::byId,
                        bookingPaymentDao::upsert,
                    ) { it.id }
                "payment_reminders" -> {
                    val model = json.decodeFromJsonElement(PaymentReminder.serializer(), row)
                    // kind is Room-only state (ADR-020); preserve it across pulled updates.
                    val kind = paymentReminderDao.byId(model.id)?.kind ?: ReminderKind.PAYMENT
                    upsertIfChanged(model.toEntity(kind), paymentReminderDao::byId, paymentReminderDao::upsert) { it.id }
                }
                "parties" ->
                    upsertIfChanged(
                        json.decodeFromJsonElement(Party.serializer(), row).toEntity(),
                        partyDao::byId,
                        partyDao::upsert,
                    ) { it.id }
                "expenses" ->
                    upsertIfChanged(
                        json.decodeFromJsonElement(Expense.serializer(), row).toEntity(),
                        expenseDao::byId,
                        expenseDao::upsert,
                    ) { it.id }
                "expense_attachments" -> {
                    val model = json.decodeFromJsonElement(ExpenseAttachment.serializer(), row)
                    // local_cache_path and drive_permission_ensured are Room-only state;
                    // preserve both across pulled updates (ADR-052 / ADR-059).
                    val existing = expenseAttachmentDao.byId(model.id)
                    upsertIfChanged(
                        model.toEntity(existing?.localCachePath, existing?.drivePermissionEnsured ?: false),
                        expenseAttachmentDao::byId,
                        expenseAttachmentDao::upsert,
                    ) { it.id }
                }
                "master_items" -> {
                    val model = json.decodeFromJsonElement(MasterItem.serializer(), row)
                    // drive_permission_ensured is Room-only state; preserve it across
                    // pulled updates while the Drive photo is unchanged (ADR-063 —
                    // the exact expense_attachments ADR-059 shape). A pulled row with a
                    // NEW drive_image_id resets the flag so the repair pass re-runs.
                    val existing = masterItemDao.byId(model.id)
                    val keepEnsured =
                        existing != null && existing.driveImageId == model.driveImageId && existing.drivePermissionEnsured
                    upsertIfChanged(
                        model.toEntity(drivePermissionEnsured = keepEnsured),
                        masterItemDao::byId,
                        masterItemDao::upsert,
                    ) { it.id }
                }
                "inventory_transactions" ->
                    upsertIfChanged(
                        json.decodeFromJsonElement(InventoryTransaction.serializer(), row).toEntity(),
                        inventoryTransactionDao::byId,
                        inventoryTransactionDao::upsert,
                    ) { it.id }
                else -> error("unknown synced table: $table")
            }

        /** Data-class equality against the stored row; identical rows skip the write. */
        private suspend fun <E : Any> upsertIfChanged(
            incoming: E,
            read: suspend (String) -> E?,
            write: suspend (E) -> Unit,
            idOf: (E) -> String,
        ): Boolean {
            if (read(idOf(incoming)) == incoming) return false
            write(incoming)
            return true
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
