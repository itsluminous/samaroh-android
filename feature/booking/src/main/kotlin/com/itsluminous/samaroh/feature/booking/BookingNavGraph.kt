package com.itsluminous.samaroh.feature.booking

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.itsluminous.samaroh.core.data.navigation.CurrentScreenTracker
import com.itsluminous.samaroh.core.data.navigation.HelpDeepLinkIds
import com.itsluminous.samaroh.core.data.navigation.HelpScreenIds
import com.itsluminous.samaroh.feature.booking.reminders.reminderDeepLinks
import com.itsluminous.samaroh.feature.booking.ui.calendar.BookingCalendarScreen
import com.itsluminous.samaroh.feature.booking.ui.form.BookingFormScreen
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate

/** Route of the Booking tab's start destination. */
const val BOOKING_ROUTE = "booking"

private const val CALENDAR_ROUTE = "booking/calendar"
private const val FORM_ROUTE = "booking/form?bookingId={bookingId}&date={date}"

private fun formRoute(
    bookingId: String? = null,
    date: LocalDate? = null,
): String = "booking/form?bookingId=${bookingId.orEmpty()}&date=${date?.toString().orEmpty()}"

/**
 * Booking feature graph (§4.1, W1-A): calendar-first booking management. The feature is
 * self-contained — it hosts its own internal NavHost; the Wave-0 `bookingGraph()` call
 * signature is preserved.
 *
 * Reminder deep links (§4.1, ADR-098) do NOT arrive as parameters: the shell parks them
 * in the singleton `BookingDeepLinkHolder` and the calendar ViewModel applies them (date
 * + card — the same card a calendar tap opens, never the edit form). Threading them
 * through this builder would rebuild the shell graph and re-create this host.
 *
 * @param helpTarget a Help "Show me" deep link (ADR-108) this host lands on:
 *   `booking/calendar` pops to the calendar, `booking/form` opens the blank add form.
 *   (`booking/card` is landed by the app shell through the ADR-098 holder — nearest
 *   booking's card.) A [State] (ADR-099); consumed via [onHelpTargetConsumed].
 * @param onHelpTargetConsumed clears the pending Show-me target once landed.
 */
fun NavGraphBuilder.bookingGraph(
    helpTarget: State<String?> = mutableStateOf(null),
    onHelpTargetConsumed: () -> Unit = {},
) {
    composable(BOOKING_ROUTE) {
        val pendingHelpTarget by helpTarget
        BookingFeatureHost(helpTarget = pendingHelpTarget, onHelpTargetConsumed = onHelpTargetConsumed)
    }
}

@Composable
private fun BookingFeatureHost(
    helpTarget: String?,
    onHelpTargetConsumed: () -> Unit,
) {
    val navController = rememberNavController()
    // Help "Show me" (ADR-108): land on the calendar or the blank add form, then consume.
    LaunchedEffect(helpTarget) {
        when (helpTarget) {
            HelpDeepLinkIds.BOOKING_CALENDAR -> navController.popBackStack(CALENDAR_ROUTE, inclusive = false)
            HelpDeepLinkIds.BOOKING_FORM -> {
                navController.popBackStack(CALENDAR_ROUTE, inclusive = false)
                navController.navigate(formRoute()) { launchSingleTop = true }
            }
            else -> return@LaunchedEffect
        }
        onHelpTargetConsumed()
    }
    // A warm deep link may arrive while the add/edit form is open: return to the
    // calendar so the card has a host (the calendar ViewModel consumes the link itself).
    val context = LocalContext.current
    val pendingLink by remember(context) { reminderDeepLinks(context)?.pending ?: MutableStateFlow(null) }
        .collectAsStateWithLifecycle()
    LaunchedEffect(pendingLink) {
        if (pendingLink != null) navController.popBackStack(CALENDAR_ROUTE, inclusive = false)
    }
    // Help/assistant context (ADR-108): report the inner destination; the calendar screen
    // layers its sheets (card / record payment) on top via the tracker's overlay.
    val currentRoute =
        navController
            .currentBackStackEntryAsState()
            .value
            ?.destination
            ?.route
    LaunchedEffect(currentRoute) {
        when (currentRoute) {
            CALENDAR_ROUTE -> CurrentScreenTracker.setScreen(HelpScreenIds.BOOKING_CALENDAR)
            FORM_ROUTE -> CurrentScreenTracker.setScreen(HelpScreenIds.BOOKING_FORM)
        }
    }
    NavHost(navController = navController, startDestination = CALENDAR_ROUTE) {
        composable(CALENDAR_ROUTE) {
            BookingCalendarScreen(
                onAddBooking = { date -> navController.navigate(formRoute(date = date)) },
                onEditBooking = { bookingId -> navController.navigate(formRoute(bookingId = bookingId)) },
            )
        }
        composable(
            route = FORM_ROUTE,
            arguments =
                listOf(
                    navArgument("bookingId") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("date") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
        ) {
            BookingFormScreen(onDone = { navController.popBackStack() })
        }
    }
}
