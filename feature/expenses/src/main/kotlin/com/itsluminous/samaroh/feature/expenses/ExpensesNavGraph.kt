package com.itsluminous.samaroh.feature.expenses

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.itsluminous.samaroh.core.designsystem.component.PlaceholderScreen
import com.itsluminous.samaroh.core.i18n.R

/** Route of the Expenses tab's start destination. */
const val EXPENSES_ROUTE = "expenses"

/**
 * Expenses feature graph (Wave 0 skeleton — W1-B implements the party ledger:
 * party list/search, running-balance ledger, entries, attachments).
 */
fun NavGraphBuilder.expensesGraph() {
    composable(EXPENSES_ROUTE) {
        PlaceholderScreen(featureNameRes = R.string.common_nav_expenses, icon = Icons.Filled.AccountBalanceWallet)
    }
}
