package com.itsluminous.samaroh.core.designsystem.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Viewer action-bar state & gating (ADR-053): which actions render, the immersive
 * fullscreen toggle (chrome hides; tap restores), and the delete confirmation flow.
 */
@RunWith(RobolectricTestRunner::class)
class ImageViewerDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val image = File.createTempFile("viewer", ".jpg").apply { writeBytes(ByteArray(16)) }

    @Test
    fun `download and delete are hidden when not offered`() {
        composeRule.setContent {
            ImageViewerDialog(
                model = image,
                contentDescription = "photo",
                onDismiss = {},
            )
        }
        composeRule.onNodeWithTag(FULLSCREEN_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(CLOSE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(DOWNLOAD_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(DELETE_TAG).assertDoesNotExist()
    }

    @Test
    fun `download shows for a local source and delete shows when an action is provided`() {
        composeRule.setContent {
            ImageViewerDialog(
                model = image,
                contentDescription = "photo",
                onDismiss = {},
                downloadSource = image,
                deleteAction = ImageViewerDeleteAction("Delete?", "It will be removed.", onConfirmed = {}),
            )
        }
        composeRule.onNodeWithTag(DOWNLOAD_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(DELETE_TAG).assertIsDisplayed()
    }

    @Test
    fun `fullscreen hides the chrome and tapping the image restores it`() {
        composeRule.setContent {
            ImageViewerDialog(
                model = image,
                contentDescription = "photo",
                onDismiss = {},
            )
        }
        composeRule.onNodeWithTag(FULLSCREEN_TAG).performClick()
        composeRule.onNodeWithTag(ACTION_BAR_TAG).assertDoesNotExist()

        composeRule.onNodeWithContentDescription("photo").performClick()
        composeRule.onNodeWithTag(ACTION_BAR_TAG).assertIsDisplayed()
    }

    @Test
    fun `tap outside fullscreen dismisses - the pre-existing expand-dialog gesture`() {
        var dismissed = false
        composeRule.setContent {
            ImageViewerDialog(
                model = image,
                contentDescription = "photo",
                onDismiss = { dismissed = true },
            )
        }
        composeRule.onNodeWithContentDescription("photo").performClick()
        assertThat(dismissed).isTrue()
    }

    @Test
    fun `delete asks for confirmation and only a confirmed delete runs the action`() {
        var confirmed = false
        composeRule.setContent {
            ImageViewerDialog(
                model = image,
                contentDescription = "photo",
                onDismiss = {},
                deleteAction =
                    ImageViewerDeleteAction(
                        confirmTitle = "Delete this bill?",
                        confirmMessage = "The bill will be removed.",
                        onConfirmed = { confirmed = true },
                    ),
            )
        }
        composeRule.onNodeWithTag(DELETE_TAG).performClick()
        composeRule.onNodeWithText("Delete this bill?").assertIsDisplayed()
        composeRule.onNodeWithText("The bill will be removed.").assertIsDisplayed()
        assertThat(confirmed).isFalse()

        composeRule.onNodeWithTag(DELETE_CONFIRM_TAG).performClick()
        assertThat(confirmed).isTrue()
    }
}
