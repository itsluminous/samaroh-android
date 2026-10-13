package com.itsluminous.samaroh.core.data.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Record an expense against THIS booking" request (ADR-101, revised by ADR-106; shared
 * `docs/expense-against-event.md` §1). Built by the Booking card, consumed by the
 * Expenses add-entry form. Carries only what the form pre-fills; the EVENT PARTY itself is
 * resolved/created on SAVE (§2.2: by link → by name → create), never on open.
 *
 * Lives in `core:data` because feature modules never depend on each other: Booking
 * writes it, Expenses reads it, the app shell only watches it to switch tabs.
 */
@Serializable
data class BookingExpenseDraft(
    @SerialName("booking_id") val bookingId: String,
    @SerialName("business_id") val businessId: String,
    /**
     * The event party's deterministic ENGLISH name (§2.1, `core:i18n EventPartyName`:
     * `{EventTypeLabel} - {dd-MMM-yyyy} - {CustomerName}`) — shown read-only on the form,
     * matched by normalized name (§2.2 step 2) and used verbatim for a created party.
     */
    @SerialName("event_party_name") val eventPartyName: String,
    /** `bookings.customer_phone`, null when blank — phone for a NEWLY created party only. */
    @SerialName("customer_phone") val customerPhone: String? = null,
    /**
     * The pre-filled notes, already localized by the Booking side
     * (`expenses.entry.booking_notes_default` = `{eventType} · {dates}`); the user may
     * edit or clear it.
     */
    @SerialName("default_notes") val defaultNotes: String,
)

/**
 * Parks the pending [BookingExpenseDraft] OUTSIDE Compose state (ADR-098/ADR-099 holder
 * pattern): the Booking card sets it, the shell switches to the Expenses tab, the
 * Expenses tab host navigates to the add-entry form, and the form's ViewModel [consume]s
 * it once it has copied the fields into its own saved state.
 */
@Singleton
class BookingExpenseDraftHolder
    @Inject
    constructor() {
        private val _pending = MutableStateFlow<BookingExpenseDraft?>(null)

        /** The draft waiting to be picked up; null when nothing is pending. */
        val pending: StateFlow<BookingExpenseDraft?> = _pending

        fun set(draft: BookingExpenseDraft) {
            _pending.value = draft
        }

        /** Clears [draft] if it is still the pending one (a newer draft is left alone). */
        fun consume(draft: BookingExpenseDraft) {
            _pending.compareAndSet(draft, null)
        }

        /** Drops whatever is pending (the shell: no Expenses tab to land on). */
        fun clear() {
            _pending.value = null
        }
    }

/**
 * "Open THIS booking's card" from outside the Booking module (ADR-106 §3 — the ledger's
 * "Show event" action). The app binds it to the ADR-098 `BookingDeepLinkHolder` route, so
 * the tap lands exactly where a reminder tap lands: the booking's month with its card open
 * (deleted booking → the date, no card; foreign business → nothing).
 */
interface BookingCardOpener {
    fun openBookingCard(
        bookingId: String,
        businessId: String,
    )
}
