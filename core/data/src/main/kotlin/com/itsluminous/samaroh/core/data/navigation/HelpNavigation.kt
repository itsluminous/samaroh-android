package com.itsluminous.samaroh.core.data.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stable screen ids of the single-source user guide (`shared/help/guide.json` →
 * `screens[].id`, ADR-108). They are public URL segments of the help site and the key the
 * Android destination → help mapping uses; NEVER rename one here without renaming it in
 * the guide (a unit test in `core:help` cross-checks every id below against the guide).
 *
 * Lives in `core:data` so every feature host can report "this is the screen on display"
 * through [CurrentScreenTracker] without a new module dependency (feature modules depend
 * on `core:*` only).
 */
object HelpScreenIds {
    const val APP_SHELL = "app_shell"
    const val BOOKING_CALENDAR = "booking_calendar"
    const val BOOKING_FORM = "booking_form"
    const val BOOKING_CARD = "booking_card"
    const val RECORD_PAYMENT = "record_payment"
    const val EXPENSES_HOME = "expenses_home"
    const val EXPENSES_LEDGER = "expenses_ledger"
    const val ADD_EXPENSE = "add_expense"
    const val INVENTORY_STOCK = "inventory_stock"
    const val INVENTORY_MASTERLIST = "inventory_masterlist"
    const val INVENTORY_ITEM = "inventory_item"
    const val NOTES = "notes"
    const val FILES = "files"
    const val MENU = "menu"
    const val SETTINGS = "settings"
    const val MEMBERS = "members"
    const val REPORTS = "reports"
    const val SIGN_IN = "sign_in"

    /**
     * The in-app Help screen itself — NOT a guide screen (there is no help page about the
     * help page). The tracker reports it so the shell can hide the contextual `?` while
     * Help is open; the assistant context treats it like any unknown screen (index page).
     */
    const val HELP = "help"

    /** Every guide screen id (excludes the [HELP] sentinel). */
    val all: List<String> =
        listOf(
            APP_SHELL,
            BOOKING_CALENDAR,
            BOOKING_FORM,
            BOOKING_CARD,
            RECORD_PAYMENT,
            EXPENSES_HOME,
            EXPENSES_LEDGER,
            ADD_EXPENSE,
            INVENTORY_STOCK,
            INVENTORY_MASTERLIST,
            INVENTORY_ITEM,
            NOTES,
            FILES,
            MENU,
            SETTINGS,
            MEMBERS,
            REPORTS,
            SIGN_IN,
        )
}

/**
 * Platform-neutral deep-link ids of the guide (`shared/help/routes.json` → `routes` keys,
 * ADR-108). A guide task's "Show me" button sets one of these on [HelpNavigationHolder];
 * the app shell switches to the owning tab / Menu and the feature hosts land on the exact
 * inner screen (or open the sheet) for the ids they own. `core:help`'s drift test asserts
 * every id in `routes.json` is listed here AND resolved by the app shell.
 */
object HelpDeepLinkIds {
    const val BOOKING_CALENDAR = "booking/calendar"
    const val BOOKING_FORM = "booking/form"
    const val BOOKING_CARD = "booking/card"
    const val EXPENSES_HOME = "expenses/home"
    const val EXPENSES_ADD_PARTY = "expenses/add_party"
    const val EXPENSES_LEDGER = "expenses/ledger"
    const val EXPENSES_ADD_ENTRY = "expenses/add_entry"
    const val INVENTORY_STOCK = "inventory/stock"
    const val INVENTORY_MASTERLIST = "inventory/masterlist"
    const val INVENTORY_ITEM = "inventory/item"
    const val NOTES_HOME = "notes/home"
    const val FILES_HOME = "files/home"
    const val MENU_HOME = "menu/home"
    const val MENU_SETTINGS = "menu/settings"
    const val MENU_SETTINGS_LANGUAGE = "menu/settings/language"
    const val MENU_SETTINGS_SYNC = "menu/settings/sync"
    const val MENU_SETTINGS_BUSINESS = "menu/settings/business"
    const val MENU_SETTINGS_EVENT_TYPES = "menu/settings/event_types"
    const val MENU_SETTINGS_REMINDERS = "menu/settings/reminders"
    const val MENU_MEMBERS = "menu/members"
    const val MENU_ABOUT = "menu/about"
    const val REPORTS_HOME = "reports/home"
    const val REPORTS_DETAIL = "reports/detail"
    const val AUTH_SIGN_IN = "auth/sign_in"

    val all: List<String> =
        listOf(
            BOOKING_CALENDAR,
            BOOKING_FORM,
            BOOKING_CARD,
            EXPENSES_HOME,
            EXPENSES_ADD_PARTY,
            EXPENSES_LEDGER,
            EXPENSES_ADD_ENTRY,
            INVENTORY_STOCK,
            INVENTORY_MASTERLIST,
            INVENTORY_ITEM,
            NOTES_HOME,
            FILES_HOME,
            MENU_HOME,
            MENU_SETTINGS,
            MENU_SETTINGS_LANGUAGE,
            MENU_SETTINGS_SYNC,
            MENU_SETTINGS_BUSINESS,
            MENU_SETTINGS_EVENT_TYPES,
            MENU_SETTINGS_REMINDERS,
            MENU_MEMBERS,
            MENU_ABOUT,
            REPORTS_HOME,
            REPORTS_DETAIL,
            AUTH_SIGN_IN,
        )

    /** The ids the Booking feature host / calendar land on itself. */
    val booking: Set<String> = setOf(BOOKING_CALENDAR, BOOKING_FORM, BOOKING_CARD)

    /** The ids the Expenses tab host lands on (argument-carrying routes open the party list). */
    val expenses: Set<String> = setOf(EXPENSES_HOME, EXPENSES_ADD_PARTY, EXPENSES_LEDGER, EXPENSES_ADD_ENTRY)

    /** The ids the Inventory host lands on (item detail needs an id → the stock list). */
    val inventory: Set<String> = setOf(INVENTORY_STOCK, INVENTORY_MASTERLIST, INVENTORY_ITEM)

    /** The ids the Menu host lands on (its own nested graph). */
    val menu: Set<String> =
        setOf(
            MENU_HOME,
            MENU_SETTINGS,
            MENU_SETTINGS_LANGUAGE,
            MENU_SETTINGS_SYNC,
            MENU_SETTINGS_BUSINESS,
            MENU_SETTINGS_EVENT_TYPES,
            MENU_SETTINGS_REMINDERS,
            MENU_MEMBERS,
            MENU_ABOUT,
        )
}

/**
 * What is on screen right now, as the help system sees it (ADR-108).
 *
 * @property screen the guide id of the visible destination (a [HelpScreenIds] constant),
 *   null before the first destination reported.
 * @property overlay a sheet open OVER that destination that has its own guide screen
 *   (booking card, record payment), or null. Cleared whenever a new [screen] is reported.
 */
data class CurrentScreen(
    val screen: String? = null,
    val overlay: String? = null,
) {
    /** The id the assistant context and the contextual `?` resolve: the overlay wins. */
    val effective: String? get() = overlay ?: screen
}

/**
 * Process-wide "which screen is the user looking at" (ADR-108). Feature hosts report
 * their inner destination with [setScreen] (the nav graph of the shell only knows the TAB,
 * every feature runs its own nested `NavHost`), screens hosting a sheet with its own guide
 * page report it with [setOverlay]. `MainActivity.onProvideAssistContent` and the shell's
 * contextual `?` read [state].
 *
 * A plain singleton on purpose: it has no dependencies, every feature host touches it
 * from composition, and `MainActivity` reads it synchronously when the assistant asks.
 * Writers only ever SET (last writer wins) — a leaving destination never clears, because
 * during a nav transition the incoming destination reports first and the outgoing one is
 * disposed afterwards; clearing on dispose would wipe the fresh value.
 */
object CurrentScreenTracker {
    private val _state = MutableStateFlow(CurrentScreen())

    val state: StateFlow<CurrentScreen> = _state.asStateFlow()

    /** The visible destination changed: record it and drop any overlay of the previous one. */
    fun setScreen(screenId: String) {
        _state.value = CurrentScreen(screen = screenId, overlay = null)
    }

    /** A sheet with its own guide page opened ([overlayId]) or closed (null) over the current screen. */
    fun setOverlay(overlayId: String?) {
        _state.update { it.copy(overlay = overlayId) }
    }

    /** Test hook: back to "nothing reported". */
    fun reset() {
        _state.value = CurrentScreen()
    }
}

/** A guide task's "Show me" request: land where [deepLinkId] (a [HelpDeepLinkIds] constant) points. */
data class HelpTarget(
    val deepLinkId: String,
)

/**
 * Parks the pending "Show me" navigation OUTSIDE Compose state (ADR-098/ADR-099 holder
 * pattern, ADR-108): the Help screen (feature:menu) sets it, the app shell switches to the
 * owning tab (or stays in the Menu) and hands the id to the feature host as a `State`,
 * which lands on the inner screen and [consume]s it. Argument-carrying routes
 * (`expenses/ledger/{partyId}`, …) land on the parent list; `booking/card` lands on the
 * nearest booking's card through the ADR-098 `BookingDeepLinkHolder` (app wiring).
 */
@Singleton
class HelpNavigationHolder
    @Inject
    constructor() {
        private val _pending = MutableStateFlow<HelpTarget?>(null)

        /** The target waiting to be applied; null when nothing is pending. */
        val pending: StateFlow<HelpTarget?> = _pending

        fun set(target: HelpTarget) {
            _pending.value = target
        }

        /** Clears [target] if it is still the pending one (a newer target is left alone). */
        fun consume(target: HelpTarget) {
            _pending.compareAndSet(target, null)
        }

        /** Drops whatever is pending (the shell: the owning tab is hidden for this member). */
        fun clear() {
            _pending.value = null
        }
    }
