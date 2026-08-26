package com.itsluminous.samaroh.e2e

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.itsluminous.samaroh.core.i18n.AmountFormatter
import com.itsluminous.samaroh.core.i18n.R
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Test
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols

/**
 * §13 acceptance 8: add a master item, add 10 @ ₹100, remove 3 → stock 7, FIFO value
 * ₹700 — through the real dialogs including the debounced type-ahead item picker.
 */
abstract class InventoryFlowTest(
    localeTag: String,
) : LocalizedE2eTest(localeTag) {
    override suspend fun seed() {
        seedOnboardedBusiness()
    }

    /** Mirrors feature:inventory formatQuantity (locale-aware `0.###`). */
    private fun quantity(value: Double): String = DecimalFormat("0.###", DecimalFormatSymbols(testLocale)).format(value)

    private fun fillField(
        label: String,
        value: String,
    ) {
        compose.onNode(hasSetTextAction() and hasText(label)).performTextInput(value)
    }

    private fun recordTransaction(
        itemPrefix: String,
        itemName: String,
        remove: Boolean,
        qty: String,
        unitPrice: String? = null,
    ) {
        // The IME from the previous dialog's text fields can overlay the FAB.
        androidx.test.espresso.Espresso
            .closeSoftKeyboard()
        compose.waitForIdle()
        waitForContentDescription(string(R.string.inventory_fab_record_transaction)).performClick()
        waitForText(string(R.string.inventory_txn_title))
        fillField(string(R.string.inventory_txn_item_label), itemPrefix)
        waitForSuggestion(itemName).performClick() // debounced type-ahead suggestion (§4.3)
        if (remove) compose.onNode(hasText(string(R.string.inventory_txn_type_remove))).performClick()
        fillField(string(R.string.inventory_txn_quantity_label), qty)
        unitPrice?.let { fillField(string(R.string.inventory_txn_unit_price_label), it) }
        compose.onNode(hasText(string(R.string.common_action_save))).performClick()
        waitUntilGone(string(R.string.inventory_txn_title))
    }

    @Test
    fun masterItemThenFifoTransactions_stockAndValueCorrect() {
        waitForText(string(R.string.common_nav_inventory)).performClick()
        waitForText(string(R.string.inventory_list_title))

        // Masterlist: create the master item (default unit = pieces).
        waitForContentDescription(string(R.string.inventory_toggle_masterlist)).performClick()
        waitForContentDescription(string(R.string.inventory_masterlist_add_title)).performClick()
        waitForText(string(R.string.inventory_masterlist_add_title))
        fillField(string(R.string.inventory_masterlist_name_label), "Steel Plates")
        compose.onNode(hasText(string(R.string.common_action_save))).performClick()
        waitForText("Steel Plates")

        // Back to stock; add 10 @ ₹100 then remove 3 — FIFO leaves 7 worth ₹700.
        waitForContentDescription(string(R.string.inventory_toggle_stock)).performClick()
        recordTransaction("Steel", "Steel Plates", remove = false, qty = "10", unitPrice = "100")
        waitForText(
            string(R.string.inventory_list_quantity_with_unit, quantity(10.0), string(R.string.inventory_masterlist_unit_pieces)),
        )
        recordTransaction("Steel", "Steel Plates", remove = true, qty = "3")

        waitForText(
            string(R.string.inventory_list_quantity_with_unit, quantity(7.0), string(R.string.inventory_masterlist_unit_pieces)),
        )
        waitForText(AmountFormatter.format(700_00L))
    }
}

@HiltAndroidTest
class InventoryFlowEnTest : InventoryFlowTest("en")

@HiltAndroidTest
class InventoryFlowHiTest : InventoryFlowTest("hi")
