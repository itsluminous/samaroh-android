package com.itsluminous.samaroh.feature.expenses

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.itsluminous.samaroh.core.model.ExpenseDirection
import com.itsluminous.samaroh.feature.expenses.addentry.ARG_DIRECTION
import com.itsluminous.samaroh.feature.expenses.addentry.ARG_EXPENSE_ID
import com.itsluminous.samaroh.feature.expenses.addentry.ARG_FROM_SHARE
import com.itsluminous.samaroh.feature.expenses.addentry.AddEntryScreen
import com.itsluminous.samaroh.feature.expenses.addperson.AddPersonScreen
import com.itsluminous.samaroh.feature.expenses.home.ExpensesHomeScreen
import com.itsluminous.samaroh.feature.expenses.ledger.ARG_PARTY_ID
import com.itsluminous.samaroh.feature.expenses.ledger.PartyLedgerScreen
import com.itsluminous.samaroh.feature.expenses.sharetarget.SharePartyPickerScreen

/** Route of the Expenses tab's start destination. */
const val EXPENSES_ROUTE = "expenses"

private const val ROUTE_HOME = "expenses/home"
private const val ROUTE_ADD_PERSON = "expenses/add_person"
private const val ROUTE_LEDGER = "expenses/ledger/{$ARG_PARTY_ID}"
private const val ROUTE_ADD_ENTRY =
    "expenses/entry/{$ARG_PARTY_ID}/{$ARG_DIRECTION}?$ARG_EXPENSE_ID={$ARG_EXPENSE_ID}&$ARG_FROM_SHARE={$ARG_FROM_SHARE}"

/** Create-invoice share-target party picker (ADR-078). */
private const val ROUTE_SHARE_PICK = "expenses/share_pick"

private fun ledgerRoute(partyId: String) = "expenses/ledger/$partyId"

private fun addEntryRoute(
    partyId: String,
    direction: ExpenseDirection,
    expenseId: String? = null,
    fromShare: Boolean = false,
) = "expenses/entry/$partyId/${direction.wire}?$ARG_EXPENSE_ID=${expenseId.orEmpty()}&$ARG_FROM_SHARE=$fromShare"

/**
 * Expenses feature graph (§4.2). The outer signature is the Wave-0 contract the app shell
 * wires; internal navigation (home → add-person / ledger → add-entry) runs on a nested
 * NavHost so the bottom-bar destination stays a single route.
 *
 * @param partyIdToOpen party ledger to open directly (web App Link, ADR-033); unknown
 *   ids gracefully stay on the party list. Cleared via [onPartyDeepLinkConsumed].
 * @param onPartyDeepLinkConsumed clears the pending party id once handled.
 * @param shareTargetRequested a file was shared to the Create-invoice target (ADR-078):
 *   route to the party picker, then add-entry with the file pre-attached. Cleared via
 *   [onShareTargetConsumed].
 * @param onShareTargetConsumed clears the pending share-target request once routed.
 */
fun NavGraphBuilder.expensesGraph(
    partyIdToOpen: String? = null,
    onPartyDeepLinkConsumed: () -> Unit = {},
    shareTargetRequested: Boolean = false,
    onShareTargetConsumed: () -> Unit = {},
) {
    composable(EXPENSES_ROUTE) {
        ExpensesTabNavHost(
            partyIdToOpen = partyIdToOpen,
            onPartyDeepLinkConsumed = onPartyDeepLinkConsumed,
            shareTargetRequested = shareTargetRequested,
            onShareTargetConsumed = onShareTargetConsumed,
        )
    }
}

@Composable
private fun ExpensesTabNavHost(
    partyIdToOpen: String?,
    onPartyDeepLinkConsumed: () -> Unit,
    shareTargetRequested: Boolean,
    onShareTargetConsumed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val deepLinkViewModel: ExpensesDeepLinkViewModel = hiltViewModel()
    // App-Link ledger target (ADR-033): only navigate when the party exists locally —
    // a stale/foreign id lands on the party list instead of an empty ledger.
    LaunchedEffect(partyIdToOpen) {
        if (partyIdToOpen != null) {
            if (deepLinkViewModel.partyExists(partyIdToOpen)) {
                navController.navigate(ledgerRoute(partyIdToOpen)) { launchSingleTop = true }
            }
            onPartyDeepLinkConsumed()
        }
    }
    // Create-invoice share target (ADR-078): the shared file waits in the holder; the
    // picker (which also carries the signed-in/permission gates) is the flow's door.
    LaunchedEffect(shareTargetRequested) {
        if (shareTargetRequested) {
            navController.navigate(ROUTE_SHARE_PICK) { launchSingleTop = true }
            onShareTargetConsumed()
        }
    }
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
                onEditEntry = { direction, expenseId ->
                    navController.navigate(addEntryRoute(partyId, direction, expenseId))
                },
            )
        }
        composable(
            route = ROUTE_ADD_ENTRY,
            arguments =
                listOf(
                    navArgument(ARG_PARTY_ID) { type = NavType.StringType },
                    navArgument(ARG_DIRECTION) { type = NavType.StringType },
                    navArgument(ARG_EXPENSE_ID) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument(ARG_FROM_SHARE) {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                ),
        ) {
            AddEntryScreen(
                onDone = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
        composable(ROUTE_SHARE_PICK) {
            SharePartyPickerScreen(
                onPartyPicked = { partyId ->
                    // The shared file rides the holder; the add-entry screen pre-attaches
                    // it via the fromShare flag (ADR-078). Direction: an invoice you
                    // received to pay = "You gave".
                    navController.navigate(addEntryRoute(partyId, ExpenseDirection.PAID, fromShare = true)) {
                        popUpTo(ROUTE_HOME)
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
