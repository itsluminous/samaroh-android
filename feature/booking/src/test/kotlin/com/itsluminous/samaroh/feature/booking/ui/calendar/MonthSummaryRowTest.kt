package com.itsluminous.samaroh.feature.booking.ui.calendar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.designsystem.theme.SamarohTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ADR-103: the Booking tab's month summary never wraps — on a narrow phone with long
 * (Hindi) labels and lakh-scale amounts both cells shrink their font and stay on ONE line.
 */
@RunWith(RobolectricTestRunner::class)
class MonthSummaryRowTest {
    @get:Rule
    val compose = createComposeRule()

    private val received = "प्राप्त ₹1,06,51,161.50"
    private val pending = "बाकी ₹99,99,999.75"

    private val width = mutableStateOf(240.dp)

    private fun setRow(initial: Dp) {
        width.value = initial
        compose.setContent {
            SamarohTheme {
                Box(Modifier.width(width.value)) {
                    MonthSummaryRow(receivedText = received, pendingText = pending)
                }
            }
        }
    }

    private fun SemanticsNodeInteraction.lineCount(): Int {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single().lineCount
    }

    @Test
    fun `narrow width keeps both cells on one line and inside the row`() {
        setRow(240.dp)
        val receivedNode = compose.onNodeWithTag(SUMMARY_RECEIVED_TAG)
        val pendingNode = compose.onNodeWithTag(SUMMARY_PENDING_TAG)
        receivedNode.assertIsDisplayed()
        pendingNode.assertIsDisplayed()
        assertThat(receivedNode.lineCount()).isEqualTo(1)
        assertThat(pendingNode.lineCount()).isEqualTo(1)
        // Both cells fit the 240dp row: the pending cell ends within the box.
        assertThat(pendingNode.getUnclippedBoundsInRoot().right.value).isAtMost(240f)
        assertThat(receivedNode.getUnclippedBoundsInRoot().right.value).isAtMost(pendingNode.getUnclippedBoundsInRoot().left.value)
    }

    @Test
    fun `narrow row is no taller than a wide one — shrink, not wrap`() {
        setRow(240.dp)
        val narrowHeight = compose.onNodeWithTag(SUMMARY_RECEIVED_TAG).getUnclippedBoundsInRoot().height
        width.value = 800.dp
        compose.waitForIdle()
        val wideHeight = compose.onNodeWithTag(SUMMARY_RECEIVED_TAG).getUnclippedBoundsInRoot().height
        assertThat(narrowHeight.value).isAtMost(wideHeight.value)
    }
}
