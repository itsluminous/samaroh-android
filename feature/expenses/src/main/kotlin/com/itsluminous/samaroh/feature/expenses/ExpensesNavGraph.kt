package com.itsluminous.samaroh.feature.expenses

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.itsluminous.samaroh.core.model.ExpenseDirection
import com.itsluminous.samaroh.feature.expenses.addentry.ARG_DIRECTION
import com.itsluminous.samaroh.feature.expenses.addentry.AddEntryScreen
import com.itsluminous.samaroh.feature.expenses.addperson.AddPersonScreen
import com.itsluminous.samaroh.feature.expenses.home.ExpensesHomeScreen
import com.itsluminous.samaroh.feature.expenses.ledger.ARG_PARTY_ID
import com.itsluminous.samaroh.feature.expenses.ledger.PartyLedgerScreen

/** Route of the Expenses tab's start destination. */
const val EXPENSES_ROUTE = "expenses"

private const val ROUTE_HOME = "expenses/home"
private const val ROUTE_ADD_PERSON = "expenses/add_person"
private const val ROUTE_LEDGER = "expenses/ledger/{$ARG_PARTY_ID}"
private const val ROUTE_ADD_ENTRY = "expenses/entry/{$ARG_PARTY_ID}/{$ARG_DIRECTION}"

private fun ledgerRoute(partyId: String) = "expenses/ledger/$partyId"

private fun addEntryRoute(
    partyId: String,
    direction: ExpenseDirection,
) = "expenses/entry/$partyId/${direction.wire}"

/**
 * Expenses feature graph (§4.2). The outer signature is the Wave-0 contract the app shell
 * wires; internal navigation (home → add-person / ledger → add-entry) runs on a nested
 * NavHost so the bottom-bar destination stays a single route.
 */
fun NavGraphBuilder.expensesGraph() {
    composable(EXPENSES_ROUTE) {
        ExpensesTabNavHost()
    }
}

@Composable
private fun ExpensesTabNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = ROUTE_HOME,
        modifier = modifier,
    ) {
        composable(ROUTE_HOME) {
            ExpensesHomeScreen(
                onPartyClick = { partyId -> navController.navigate(ledgerRoute(partyId)) },
                onAddPerson = { navController.navigate(ROUTE_ADD_PERSON) },
            )
        }
        composable(ROUTE_ADD_PERSON) {
            AddPersonScreen(
                onBack = { navController.popBackStack() },
                onOpenLedger = { partyId ->
                    navController.navigate(ledgerRoute(partyId)) {
                        popUpTo(ROUTE_HOME)
                    }
                },
            )
        }
        composable(
            route = ROUTE_LEDGER,
            arguments = listOf(navArgument(ARG_PARTY_ID) { type = NavType.StringType }),
        ) { entry ->
            val partyId = requireNotNull(entry.arguments?.getString(ARG_PARTY_ID))
            PartyLedgerScreen(
                onBack = { navController.popBackStack() },
                onAddEntry = { direction -> navController.navigate(addEntryRoute(partyId, direction)) },
            )
        }
        composable(
            route = ROUTE_ADD_ENTRY,
            arguments =
                listOf(
                    navArgument(ARG_PARTY_ID) { type = NavType.StringType },
                    navArgument(ARG_DIRECTION) { type = NavType.StringType },
                ),
        ) {
            AddEntryScreen(
                onDone = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
