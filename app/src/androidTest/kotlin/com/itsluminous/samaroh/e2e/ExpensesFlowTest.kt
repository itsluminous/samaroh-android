package com.itsluminous.samaroh.e2e

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.itsluminous.samaroh.core.i18n.AmountFormatter
import com.itsluminous.samaroh.core.i18n.R
import com.itsluminous.samaroh.core.testing.Fixtures
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Test

/**
 * §13 acceptance 7: debounced type-ahead person suggestions, ledger entries and the
 * running balance ("balance after" chips + net-balance header).
 */
abstract class ExpensesFlowTest(
    localeTag: String,
) : LocalizedE2eTest(localeTag) {
    override suspend fun seed() {
        seedOnboardedBusiness()
        expensesRepository.saveParty(Fixtures.party(name = "Mahesh Caterers"))
    }

    private fun openExpensesTab() {
        waitForText(string(R.string.common_nav_expenses)).performClick()
        waitForText(string(R.string.expenses_home_you_gave))
    }

    private fun addEntry(
        buttonText: String,
        amountRupees: String,
    ) {
        waitForText(buttonText).performClick()
        waitForText(string(R.string.expenses_entry_amount_label), substring = true)
        compose
            .onNode(hasSetTextAction() and hasText(string(R.string.expenses_entry_amount_label)))
            .performTextInput(amountRupees)
        compose.onNode(hasText(string(R.string.common_action_save))).performClick()
        // Back on the ledger.
        waitForText(string(R.string.expenses_ledger_net_balance_label))
    }

    @Test
    fun typeAheadSteersToExisting_entriesUpdateRunningBalance() {
        openExpensesTab()
        waitForText(string(R.string.expenses_home_add_person)).performClick()

        // Debounced type-ahead (§4.2): a prefix of an existing party surfaces it.
        waitForText(string(R.string.expenses_add_person_name_label), substring = true)
        compose
            .onNode(hasSetTextAction() and hasText(string(R.string.expenses_add_person_name_label)))
            .performTextInput("Mahe")
        waitForSuggestion("Mahesh Caterers").performClick()

        // Steered straight to the existing party's ledger — no duplicate created.
        waitForText(string(R.string.expenses_ledger_net_balance_label))

        // "You gave" ₹500 → running balance ₹500.
        addEntry(string(R.string.expenses_ledger_you_gave_button), "500")
        waitForText(string(R.string.expenses_ledger_balance_after, AmountFormatter.format(500_00L)))
        waitForText(AmountFormatter.format(500_00L))

        // "You got" ₹200 → net balance ₹300 and the newest row's balance-after chip.
        addEntry(string(R.string.expenses_ledger_you_got_button), "200")
        waitForText(string(R.string.expenses_ledger_balance_after, AmountFormatter.format(300_00L)))
        waitForText(AmountFormatter.format(300_00L))
    }

    @Test
    fun addNewPerson_opensEmptyLedger() {
        openExpensesTab()
        waitForText(string(R.string.expenses_home_add_person)).performClick()
        waitForText(string(R.string.expenses_add_person_name_label), substring = true)
        compose
            .onNode(hasSetTextAction() and hasText(string(R.string.expenses_add_person_name_label)))
            .performTextInput("Kavita Decorators")
        compose.onNode(hasText(string(R.string.common_action_save))).performClick()

        waitForText(string(R.string.expenses_ledger_empty_title))
        waitForText("Kavita Decorators")
    }
}

@HiltAndroidTest
class ExpensesFlowEnTest : ExpensesFlowTest("en")

@HiltAndroidTest
class ExpensesFlowHiTest : ExpensesFlowTest("hi")
