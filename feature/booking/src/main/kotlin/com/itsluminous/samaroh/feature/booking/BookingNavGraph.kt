package com.itsluminous.samaroh.feature.booking

import androidx.compose.runtime.Composable
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
 * self-contained — it hosts its own internal NavHost so the app shell's `bookingGraph()`
 * call signature is unchanged from Wave 0.
 */
fun NavGraphBuilder.bookingGraph() {
    composable(BOOKING_ROUTE) {
        BookingFeatureHost()
    }
}

@Composable
private fun BookingFeatureHost() {
    val navController = rememberNavController()
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
