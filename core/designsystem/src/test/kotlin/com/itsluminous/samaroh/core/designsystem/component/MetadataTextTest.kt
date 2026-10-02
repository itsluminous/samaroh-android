package com.itsluminous.samaroh.core.designsystem.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import com.itsluminous.samaroh.core.designsystem.theme.SamarohTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ADR-093 metadata text convention: the shared style is SMALLER than body copy, set in a
 * monospace face and coloured with the `outline` token, so attribution/timestamp lines are
 * visibly secondary to the content they sit under.
 */
@RunWith(RobolectricTestRunner::class)
class MetadataTextTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun style_isSmallerThanBodyAndMonospace() {
        lateinit var metadata: TextStyle
        lateinit var body: TextStyle
        compose.setContent {
            SamarohTheme(dynamicColor = false) {
                metadata = SamarohTheme.metadataTextStyle
                body = MaterialTheme.typography.bodyMedium
            }
        }
        assertEquals(FontFamily.Monospace, metadata.fontFamily)
        assertNotEquals(TextUnit.Unspecified, metadata.fontSize)
        assertTrue(
            "metadata ${metadata.fontSize} must be smaller than body ${body.fontSize}",
            metadata.fontSize.value < body.fontSize.value,
        )
    }

    @Test
    fun color_isOutlineToken_notOnSurfaceVariant() {
        var metadata: Color? = null
        var outline: Color? = null
        var onSurfaceVariant: Color? = null
        compose.setContent {
            SamarohTheme(dynamicColor = false) {
                metadata = SamarohTheme.metadataColor
                outline = MaterialTheme.colorScheme.outline
                onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
            }
        }
        assertEquals(outline, metadata)
        assertNotEquals(onSurfaceVariant, metadata)
        assertTrue(metadata != null && outline != null)
    }

    @Test
    fun component_rendersGivenTextVerbatim() {
        compose.setContent {
            SamarohTheme(dynamicColor = false) { MetadataText(text = "stamp-line") }
        }
        compose.onNodeWithText("stamp-line").assertExists()
    }
}
