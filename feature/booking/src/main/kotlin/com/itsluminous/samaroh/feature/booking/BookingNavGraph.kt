package com.itsluminous.samaroh.feature.booking

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.itsluminous.samaroh.core.designsystem.component.PlaceholderScreen
import com.itsluminous.samaroh.core.i18n.R

/** Route of the Booking tab's start destination. */
const val BOOKING_ROUTE = "booking"

/**
 * Booking feature graph (Wave 0 skeleton — W1-A implements the calendar-first booking
 * management: month view, booking CRUD, payments, reminders, date blocks).
 */
fun NavGraphBuilder.bookingGraph() {
    composable(BOOKING_ROUTE) {
        PlaceholderScreen(featureNameRes = R.string.common_nav_booking, icon = Icons.Filled.CalendarMonth)
    }
}
