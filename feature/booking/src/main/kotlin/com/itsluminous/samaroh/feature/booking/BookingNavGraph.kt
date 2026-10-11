package com.itsluminous.samaroh.feature.booking

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
 */
fun NavGraphBuilder.bookingGraph() {
    composable(BOOKING_ROUTE) { BookingFeatureHost() }
}

@Composable
private fun BookingFeatureHost() {
    val navController = rememberNavController()
    // A warm deep link may arrive while the add/edit form is open: return to the
    // calendar so the card has a host (the calendar ViewModel consumes the link itself).
    val context = LocalContext.current
    val pendingLink by remember(context) { reminderDeepLinks(context)?.pending ?: MutableStateFlow(null) }
        .collectAsStateWithLifecycle()
    LaunchedEffect(pendingLink) {
        if (pendingLink != null) navController.popBackStack(CALENDAR_ROUTE, inclusive = false)
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
