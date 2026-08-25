package com.itsluminous.samaroh.core.invoice

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import com.itsluminous.samaroh.core.data.invoice.InvoiceNumberAllocator
import com.itsluminous.samaroh.core.data.repository.BookingRepository
import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.BookingPayment
import com.itsluminous.samaroh.core.model.Business
import com.itsluminous.samaroh.core.model.PaymentMethod
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Everything both renderers (PDF + text receipt) need, loaded once. */
data class InvoiceData(
    val business: Business,
    val booking: Booking,
    val payments: List<BookingPayment>,
    val invoiceNumber: String,
    val issueDate: LocalDate,
) {
    val totalPaidPaise: Long get() = payments.sumOf { it.amountPaise }

    /** due = total − Σ(payments); computed at render time, never stored (§2). */
    val duePaise: Long get() = booking.totalAmountPaise - totalPaidPaise
}

/**
 * Loads the invoice inputs from Room and assigns the immutable invoice number on first
 * use (ADR-006: text and PDF always agree on the number).
 */
@Singleton
class InvoiceDataLoader
    @Inject
    constructor(
        private val bookingRepository: BookingRepository,
        private val businessRepository: BusinessRepository,
        private val numberAllocator: InvoiceNumberAllocator,
        private val clock: Clock,
    ) {
        suspend fun load(bookingId: String): InvoiceData {
            val invoiceNumber = numberAllocator.allocate(bookingId)
            val booking = requireNotNull(bookingRepository.booking(bookingId)) { "unknown booking: $bookingId" }
            val business = requireNotNull(businessRepository.business(booking.businessId)) { "unknown business: ${booking.businessId}" }
            val payments = bookingRepository.paymentsForBooking(bookingId).first()
            return InvoiceData(
                business = business,
                booking = booking,
                payments = payments,
                invoiceNumber = invoiceNumber,
                issueDate = LocalDate.now(clock),
            )
        }
    }

/**
 * Resolves the context whose resources follow the app's per-app locale (§5): invoice text
 * renders in the current app language. Falls back to the context's own locale when no
 * per-app override is set (also the test path).
 */
internal fun Context.withAppLocale(): Context {
    val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    if (tags.isEmpty()) return this
    val locale = Locale.forLanguageTag(tags.split(',').first())
    val config = Configuration(resources.configuration).apply { setLocale(locale) }
    return createConfigurationContext(config)
}

/** Current display locale of a (possibly locale-wrapped) context. */
internal fun Context.displayLocale(): Locale = resources.configuration.locales[0] ?: Locale.getDefault()

/**
 * Built-in event types (shared/event-types.json) map to catalog labels; a custom event
 * type is already a user-entered display label and passes through unchanged.
 */
internal object EventTypeLabels {
    private val builtIn =
        mapOf(
            "engagement" to R.string.booking_event_type_engagement,
            "tilak" to R.string.booking_event_type_tilak,
            "wedding" to R.string.booking_event_type_wedding,
            "room_booking" to R.string.booking_event_type_room_booking,
            "birthday" to R.string.booking_event_type_birthday,
            "anniversary" to R.string.booking_event_type_anniversary,
            "custom" to R.string.booking_event_type_custom,
        )

    fun label(
        context: Context,
        eventType: String,
    ): String = builtIn[eventType]?.let(context::getString) ?: eventType
}

/** Localized payment-method labels (`invoice.method.*`) — never the enum wire literal. */
internal fun PaymentMethod.labelRes(): Int =
    when (this) {
        PaymentMethod.CASH -> R.string.invoice_method_cash
        PaymentMethod.UPI -> R.string.invoice_method_upi
        PaymentMethod.BANK_TRANSFER -> R.string.invoice_method_bank_transfer
        PaymentMethod.CHEQUE -> R.string.invoice_method_cheque
        PaymentMethod.OTHER -> R.string.invoice_method_other
    }
