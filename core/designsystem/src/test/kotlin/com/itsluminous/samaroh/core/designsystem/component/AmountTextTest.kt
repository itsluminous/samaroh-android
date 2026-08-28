package com.itsluminous.samaroh.core.designsystem.component

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.itsluminous.samaroh.core.designsystem.theme.SamarohTheme
import com.itsluminous.samaroh.core.i18n.AmountFormatter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ADR-039 masked mode of the shared [AmountText]: renders ₹••• (never the value) with
 * the localized "Amount hidden" accessibility label — the ONE masking primitive every
 * module reuses when a member's per-module `view_amounts` permission is off.
 */
@RunWith(RobolectricTestRunner::class)
class AmountTextTest {
    @get:Rule
    val compose = createComposeRule()

    private val amountPaise = 1_06_51_161_00L

    @Test
    fun unmasked_rendersFormattedAmount() {
        compose.setContent {
            SamarohTheme { AmountText(amountPaise = amountPaise) }
        }
        compose.onNodeWithText(AmountFormatter.format(amountPaise)).assertExists()
    }

    @Test
    fun masked_rendersMaskInsteadOfValue() {
        compose.setContent {
            SamarohTheme { AmountText(amountPaise = amountPaise, masked = true) }
        }
        compose.onNodeWithText(AmountFormatter.MASKED).assertExists()
        // The real figure must never reach the tree.
        compose.onNodeWithText(AmountFormatter.format(amountPaise)).assertDoesNotExist()
    }

    @Test
    fun masked_announcesAmountHiddenForScreenReaders() {
        lateinit var expectedLabel: String
        compose.setContent {
            SamarohTheme {
                expectedLabel =
                    androidx.compose.ui.res.stringResource(
                        com.itsluminous.samaroh.core.i18n.R.string.auth_permissions_amount_hidden_a11y,
                    )
                AmountText(amountPaise = amountPaise, masked = true)
            }
        }
        compose.onNodeWithContentDescription(expectedLabel).assertExists()
    }
}
