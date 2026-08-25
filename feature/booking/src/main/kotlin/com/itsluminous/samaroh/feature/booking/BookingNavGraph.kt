package com.itsluminous.samaroh.feature.booking

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.itsluminous.samaroh.feature.booking.ui.calendar.BookingCalendarScreen
import com.itsluminous.samaroh.feature.booking.ui.form.BookingFormScreen
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
 * signature is preserved via default parameters.
 *
 * @param bookingIdToOpen booking to open directly (reminder-notification deep link,
 *   §4.1); the shell clears it through [onBookingOpened] once consumed.
 */
fun NavGraphBuilder.bookingGraph(
    bookingIdToOpen: String? = null,
    onBookingOpened: () -> Unit = {},
) {
    composable(BOOKING_ROUTE) {
        BookingFeatureHost(bookingIdToOpen = bookingIdToOpen, onBookingOpened = onBookingOpened)
    }
}

@Composable
private fun BookingFeatureHost(
    bookingIdToOpen: String?,
    onBookingOpened: () -> Unit,
) {
    val navController = rememberNavController()
    LaunchedEffect(bookingIdToOpen) {
        if (bookingIdToOpen != null) {
            navController.navigate(formRoute(bookingId = bookingIdToOpen))
            onBookingOpened()
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
