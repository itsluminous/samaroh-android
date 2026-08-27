package com.itsluminous.samaroh.feature.menu.ui.settings

import androidx.annotation.StringRes
import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.database.dao.SyncDisplayDao
import com.itsluminous.samaroh.core.i18n.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import javax.inject.Inject

/*
 * Human-readable rendering of outbox rows on the Sync status screen (ADR-022).
 *
 * Owner feedback: the pending-sync list must read like "Add booking — Sharma" or
 * "Update inventory item — Spoon", never raw table/UUID data. Each outbox row
 * (entity_type + operation + payload_json) resolves to a localized verb + noun plus a
 * human identifier taken from the queued payload, falling back to the local Room row
 * (queued deletes carry only a tombstone payload, but the tombstoned row still holds
 * the name), then to a short id. Technical detail stays available on tap/expand.
 */

/** Semantic identifier for one sync entry; the composable localizes/formats it. */
sealed interface SyncEntryDetail {
    /** A resolved name (customer, party, item, file...). */
    data class Text(
        val value: String,
    ) : SyncEntryDetail

    /** A date identifier (booking/date-block start date) — locale-formatted by the UI. */
    data class Date(
        val date: LocalDate,
    ) : SyncEntryDetail

    /** A money amount (paise, ADR-002) — rendered via AmountFormatter only. */
    data class Amount(
        val amountPaise: Long,
    ) : SyncEntryDetail

    /** Payment style: amount plus the booking's customer name ("₹500 for Sharma"). */
    data class AmountForName(
        val amountPaise: Long,
        val name: String,
    ) : SyncEntryDetail

    /** Nothing beyond the verb+noun phrase (e.g. business settings). */
    data object None : SyncEntryDetail
}

/** One resolved display line: localized verb + entity noun + identifier. */
data class SyncEntryDisplay(
    @StringRes val verbRes: Int,
    @StringRes val nounRes: Int,
    val detail: SyncEntryDetail,
)

/** Maps one outbox/error row to its [SyncEntryDisplay]; Room lookups fill payload gaps. */
class SyncEntryDisplayResolver
    @Inject
    constructor(
        private val lookups: SyncDisplayDao,
    ) {
        private val json = Json { ignoreUnknownKeys = true }

        suspend fun resolve(
            entityType: String,
            operation: OutboxOperation,
            payloadJson: String,
            entityId: String,
        ): SyncEntryDisplay {
            val payload = runCatching { json.parseToJsonElement(payloadJson).jsonObject }.getOrNull()
            return SyncEntryDisplay(
                verbRes = verbRes(operation, payload),
                nounRes = nounRes(entityType),
                detail = detail(entityType, payload, entityId),
            )
        }

        /**
         * Upserts split into Add vs Update by the payload timestamps: every repository
         * creates rows with `created_at == updated_at` (one shared `now`) and bumps only
         * `updated_at` on edit (LWW, §8). Payloads without `created_at`
         * (business_settings, google_accounts) always render as Update.
         */
        private fun verbRes(
            operation: OutboxOperation,
            payload: JsonObject?,
        ): Int {
            if (operation == OutboxOperation.DELETE) return R.string.settings_sync_verb_delete
            val created = payload.str("created_at")
            val updated = payload.str("updated_at")
            return if (created != null && created == updated) {
                R.string.settings_sync_verb_add
            } else {
                R.string.settings_sync_verb_update
            }
        }

        private fun nounRes(entityType: String): Int =
            when (entityType) {
                "bookings" -> R.string.settings_sync_entity_booking
                "booking_payments" -> R.string.settings_sync_entity_payment
                "payment_reminders" -> R.string.settings_sync_entity_reminder
                "date_blocks" -> R.string.settings_sync_entity_date_block
                "parties" -> R.string.settings_sync_entity_party
                "expenses" -> R.string.settings_sync_entity_expense
                "expense_attachments" -> R.string.settings_sync_entity_attachment
                "master_items" -> R.string.settings_sync_entity_inventory_item
                "inventory_transactions" -> R.string.settings_sync_entity_stock_entry
                "businesses" -> R.string.settings_sync_entity_business
                "business_members" -> R.string.settings_sync_entity_member
                "business_settings" -> R.string.settings_sync_entity_business_settings
                "google_accounts" -> R.string.settings_sync_entity_google_account
                else -> R.string.settings_sync_entity_record
            }

        private suspend fun detail(
            entityType: String,
            payload: JsonObject?,
            entityId: String,
        ): SyncEntryDetail =
            when (entityType) {
                "bookings" -> {
                    val name = payload.str("customer_name") ?: lookups.bookingCustomerName(entityId)
                    val date = (payload.str("start_date") ?: lookups.bookingStartDate(entityId)).toDateOrNull()
                    when {
                        !name.isNullOrBlank() -> SyncEntryDetail.Text(name)
                        date != null -> SyncEntryDetail.Date(date)
                        else -> shortId(entityId)
                    }
                }
                "date_blocks" -> {
                    val date = (payload.str("start_date") ?: lookups.dateBlockStartDate(entityId)).toDateOrNull()
                    if (date != null) SyncEntryDetail.Date(date) else shortId(entityId)
                }
                "booking_payments" -> {
                    val amount = payload.long("amountPaise") ?: lookups.paymentAmountPaise(entityId)
                    val bookingId = payload.str("booking_id") ?: lookups.paymentBookingId(entityId)
                    val customer = bookingId?.let { lookups.bookingCustomerName(it) }
                    when {
                        amount != null && !customer.isNullOrBlank() -> SyncEntryDetail.AmountForName(amount, customer)
                        amount != null -> SyncEntryDetail.Amount(amount)
                        else -> shortId(entityId)
                    }
                }
                "payment_reminders" -> {
                    val bookingId = payload.str("booking_id") ?: lookups.reminderBookingId(entityId)
                    val customer = bookingId?.let { lookups.bookingCustomerName(it) }
                    val date = payload.str("remind_on").toDateOrNull()
                    when {
                        !customer.isNullOrBlank() -> SyncEntryDetail.Text(customer)
                        date != null -> SyncEntryDetail.Date(date)
                        else -> shortId(entityId)
                    }
                }
                "parties" -> textOr(payload.str("name") ?: lookups.partyName(entityId), entityId)
                "expenses" -> {
                    val partyId = payload.str("party_id") ?: lookups.expensePartyId(entityId)
                    val partyName = partyId?.let { lookups.partyName(it) }
                    val amount = payload.long("amountPaise")
                    when {
                        !partyName.isNullOrBlank() -> SyncEntryDetail.Text(partyName)
                        amount != null -> SyncEntryDetail.Amount(amount)
                        else -> shortId(entityId)
                    }
                }
                "expense_attachments" -> textOr(payload.str("file_name") ?: lookups.attachmentFileName(entityId), entityId)
                "master_items" -> textOr(payload.str("name") ?: lookups.masterItemName(entityId), entityId)
                "inventory_transactions" -> {
                    val itemId = payload.str("master_item_id") ?: lookups.txnMasterItemId(entityId)
                    textOr(itemId?.let { lookups.masterItemName(it) }, entityId)
                }
                "businesses" -> textOr(payload.str("name") ?: lookups.businessName(entityId), entityId)
                "business_members" -> textOr(payload.str("display_name") ?: lookups.memberDisplayName(entityId), entityId)
                "business_settings" -> SyncEntryDetail.None
                "google_accounts" -> textOr(payload.str("email"), entityId)
                else -> shortId(entityId)
            }

        private fun textOr(
            value: String?,
            entityId: String,
        ): SyncEntryDetail = if (value.isNullOrBlank()) shortId(entityId) else SyncEntryDetail.Text(value)

        private fun shortId(entityId: String): SyncEntryDetail = SyncEntryDetail.Text(entityId.take(SHORT_ID_LENGTH))

        private fun JsonObject?.str(key: String): String? = ((this?.get(key)) as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject?.long(key: String): Long? = ((this?.get(key)) as? JsonPrimitive)?.content?.toLongOrNull()

        private fun String?.toDateOrNull(): LocalDate? = this?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

        private companion object {
            const val SHORT_ID_LENGTH = 8
        }
    }
