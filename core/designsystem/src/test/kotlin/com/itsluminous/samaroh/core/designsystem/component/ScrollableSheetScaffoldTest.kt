package com.itsluminous.samaroh.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.designsystem.theme.SamarohTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADR-107: the scaffold grows to the window, scrolls its middle and keeps header + footer
 * pinned — the contract every sheet in the app now relies on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h480dp-xhdpi")
class ScrollableSheetScaffoldTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(rows: Int) {
        compose.setContent {
            SamarohTheme {
                ScrollableSheetScaffold(
                    onDismissRequest = {},
                    header = { Text("header", modifier = Modifier.testTag("header")) },
                    footer = { Button(onClick = {}, modifier = Modifier.testTag("footer_button")) { Text("action") } },
                ) {
                    repeat(rows) { index ->
                        Box(Modifier.fillMaxWidth().height(80.dp).testTag("row_$index"))
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `tall content - header and footer pinned, last row reachable by scroll`() {
        show(rows = 20)
        compose.onNodeWithTag("header", useUnmergedTree = true).assertIsDisplayed()
        val footer = compose.onNodeWithTag("footer_button", useUnmergedTree = true)
        footer.assertIsDisplayed().assertHasClickAction()
        assertThat(footer.getBoundsInRoot().bottom.value).isAtMost(WINDOW_HEIGHT_DP)

        compose.onNodeWithTag(SHEET_SCROLL_TEST_TAG, useUnmergedTree = true).assert(hasScrollAction())
        val last = compose.onNodeWithTag("row_19", useUnmergedTree = true)
        last.assertIsNotDisplayed()
        last.performScrollTo()
        last.assertIsDisplayed()
        footer.assertIsDisplayed()
        compose.onNodeWithTag("header", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `short content - sheet hugs its content, nothing hidden`() {
        show(rows = 2)
        compose.onNodeWithTag("row_1", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("footer_button", useUnmergedTree = true).assertIsDisplayed()
        val scroll = compose.onNodeWithTag(SHEET_SCROLL_TEST_TAG, useUnmergedTree = true).getBoundsInRoot()
        // Two 80dp rows + spacing/padding — far less than the window: no growth beyond content.
        assertThat(scroll.bottom.value - scroll.top.value).isLessThan(240f)
    }

    private companion object {
        const val WINDOW_HEIGHT_DP = 480f
    }
}
