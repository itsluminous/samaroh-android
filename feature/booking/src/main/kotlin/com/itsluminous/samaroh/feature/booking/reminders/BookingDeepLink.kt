package com.itsluminous.samaroh.feature.booking.reminders

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Launch-intent extra: the booking a reminder is about (§4.1 deep link, ADR-098). */
const val EXTRA_BOOKING_ID = "com.itsluminous.samaroh.booking.EXTRA_BOOKING_ID"

/** Launch-intent extra: the business that booking belongs to (ADR-098). */
const val EXTRA_BUSINESS_ID = "com.itsluminous.samaroh.booking.EXTRA_BUSINESS_ID"

/** Launch-intent extras naming the notification the tap came from, so opening can clear it. */
const val EXTRA_OPEN_NOTIFICATION_TAG = "com.itsluminous.samaroh.booking.EXTRA_OPEN_NOTIFICATION_TAG"
const val EXTRA_OPEN_NOTIFICATION_ID = "com.itsluminous.samaroh.booking.EXTRA_OPEN_NOTIFICATION_ID"

/**
 * "Open THIS booking" request carried by every reminder surface into the app shell
 * (ADR-098): the notification body tap, the payment notification's "Partial…" action and
 * the full-screen popup's View button all build the same intent, and `MainActivity`
 * decodes it (cold start `onCreate`, warm `onNewIntent` — `singleTask`) into this value.
 *
 * [businessId] rides along so the shell can tell a reminder for another business apart
 * from one for the active business without a lookup (v1 is single-business; the id is
 * what a future business switcher needs). [ledgerKey] lets the open path acknowledge the
 * delivery itself (ADR-094) — the auto-cancel body tap already acks through the
 * `deleteIntent`, but an ACTION tap (Partial…) does not cancel the notification, so the
 * shell acks + clears it on arrival; both routes are idempotent.
 */
data class BookingDeepLink(
    val bookingId: String,
    val businessId: String? = null,
    val ledgerKey: ReminderLedgerKey? = null,
    val notificationTag: String? = null,
    val notificationId: Int = 0,
) {
    /** Writes this link's extras onto [intent] (the inverse of [from]). */
    fun applyTo(intent: Intent): Intent =
        intent.apply {
            putExtra(EXTRA_BOOKING_ID, bookingId)
            putExtra(EXTRA_BUSINESS_ID, businessId)
            putExtra(EXTRA_LEDGER_KEY, ledgerKey?.encode())
            putExtra(EXTRA_OPEN_NOTIFICATION_TAG, notificationTag)
            putExtra(EXTRA_OPEN_NOTIFICATION_ID, notificationId)
        }

    /**
     * Stable per-delivery request code for the content `PendingIntent`: the launcher
     * intents of two reminders are `filterEquals` (extras are ignored), so a shared
     * request code + `FLAG_UPDATE_CURRENT` would make the LAST posted reminder's extras
     * win on every notification for that booking (tapping an upcoming reminder would
     * then ack the payment key). Keyed by the ledger key when present, else the booking.
     */
    fun requestCode(): Int = (OPEN_PREFIX + (ledgerKey?.encode() ?: bookingId)).hashCode()

    companion object {
        private const val OPEN_PREFIX = "open:"

        /** Decodes a launch intent; null when it carries no booking id (plain launcher start). */
        fun from(intent: Intent?): BookingDeepLink? {
            val bookingId = intent?.getStringExtra(EXTRA_BOOKING_ID)?.takeIf { it.isNotBlank() } ?: return null
            return BookingDeepLink(
                bookingId = bookingId,
                businessId = intent.getStringExtra(EXTRA_BUSINESS_ID)?.takeIf { it.isNotBlank() },
                ledgerKey = ReminderLedgerKey.decode(intent.getStringExtra(EXTRA_LEDGER_KEY)),
                notificationTag = intent.getStringExtra(EXTRA_OPEN_NOTIFICATION_TAG),
                notificationId = intent.getIntExtra(EXTRA_OPEN_NOTIFICATION_ID, 0),
            )
        }
    }
}

/** Builders of the app-launch intents that carry a [BookingDeepLink] (ADR-098). */
object BookingDeepLinks {
    /**
     * The package's launcher intent carrying [link]. `singleTask` + `CLEAR_TOP` means a
     * running shell receives it through `onNewIntent`; a dead process cold-starts with it.
     * Falls back to a package-scoped MAIN/LAUNCHER intent when the resolved launcher
     * component is unavailable (library-only test manifests) — same target, same extras.
     */
    fun launchIntent(
        context: Context,
        link: BookingDeepLink,
    ): Intent {
        val launch =
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        return link.applyTo(launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    /** Content `PendingIntent` for a reminder notification: immutable, per-key request code. */
    fun pendingIntent(
        context: Context,
        link: BookingDeepLink,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            link.requestCode(),
            launchIntent(context, link),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * Arrival hook for the shell (ADR-094 §2 "tap = acknowledged"): clears the
     * notification the link came from (an action tap leaves it in the shade) and marks
     * its ledger key ACKED so no planning pass re-posts it. Idempotent with the
     * `deleteIntent` ack of an auto-cancelled body tap. Nothing to do without a key.
     */
    fun acknowledgeOpened(
        context: Context,
        link: BookingDeepLink,
    ) {
        link.notificationTag?.let { tag -> NotificationManagerCompat.from(context).cancel(tag, link.notificationId) }
        val key = link.ledgerKey ?: return
        val deps = reminderDeps(context) ?: return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                deps.snoozer().cancel(key)
                deps.ledger().ack(key, fallbackKeepUntil = defaultKeepUntil(key))
            }
        }
    }
}

/**
 * Parks the pending "open this booking" request OUTSIDE Compose state (ADR-098, same
 * shape as the ADR-086 `ShareIntakeHolder`). The shell must not thread the link through
 * the root `NavHost` builder: the builder lambda captures it, every change rebuilds the
 * graph, and navigation-compose re-creates the Booking feature host — the calendar
 * ViewModel that had just received the link is dropped with it (verified on-device).
 * Instead `MainActivity` sets the link here, the calendar ViewModel collects it, applies
 * the landing and [consume]s it only once its own state reflects it, so a ViewModel
 * recreated mid-way simply re-applies the still-pending link.
 */
@Singleton
class BookingDeepLinkHolder
    @Inject
    constructor() {
        private val _pending = MutableStateFlow<BookingDeepLink?>(null)

        /** The link waiting to be applied; null when nothing is pending. */
        val pending: StateFlow<BookingDeepLink?> = _pending

        fun set(link: BookingDeepLink) {
            _pending.value = link
        }

        /** Clears [link] if it is still the pending one (a newer link is left alone). */
        fun consume(link: BookingDeepLink) {
            _pending.compareAndSet(link, null)
        }
    }

/** The app-wide [BookingDeepLinkHolder], or null when no Hilt graph exists (see [reminderDeps]). */
fun reminderDeepLinks(context: Context): BookingDeepLinkHolder? = reminderDeps(context)?.deepLinks()
