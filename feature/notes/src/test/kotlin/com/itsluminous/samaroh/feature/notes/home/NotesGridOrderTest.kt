package com.itsluminous.samaroh.feature.notes.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.feature.notes.domain.NoteCardData
import com.itsluminous.samaroh.feature.notes.noteFixture
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Notes must fill the two-column grid ROW-MAJOR (zig-zag): newest top-left, 2nd
 * top-right, 3rd on the next row's LEFT — regardless of card heights. The old
 * staggered grid placed each card in the shortest column, so a tall card made the
 * following notes stack into one column (column-major look — owner bug report).
 */
@RunWith(RobolectricTestRunner::class)
class NotesGridOrderTest {
    @get:Rule
    val compose = createComposeRule()

    private fun card(
        id: String,
        title: String,
    ): NoteCardData = NoteCardData(note = noteFixture(id = id, title = title), tags = emptyList())

    /** Renders titles inside height-varied cards — heights must NOT change ordering. */
    private fun setGrid(cards: List<NoteCardData>) {
        compose.setContent {
            NotesGrid(
                pinned = emptyList(),
                others = cards,
                noteCard = { data ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Text(data.note.title.orEmpty())
                            // First card is deliberately TALL: under the old
                            // shortest-lane placement this pushed items 2-4 into the
                            // right column (the column-major regression).
                            if (data.note.id == "n1") {
                                Spacer(Modifier.height(300.dp))
                            }
                        }
                    }
                },
            )
        }
    }

    private fun bounds(title: String): DpRect = compose.onNodeWithText(title).getUnclippedBoundsInRoot()

    @Test
    fun `notes flow row-major - first top-left, second top-right, third next row left`() {
        setGrid(listOf(card("n1", "first"), card("n2", "second"), card("n3", "third"), card("n4", "fourth"), card("n5", "fifth")))

        val first = bounds("first")
        val second = bounds("second")
        val third = bounds("third")
        val fourth = bounds("fourth")
        val fifth = bounds("fifth")

        // Row 1: first leftmost-top, second in the right column at the same top.
        assertThat(first.left).isLessThan(second.left)
        assertThat(first.top).isEqualTo(second.top)
        // Row 2: third back in the LEFT column (same left edge as first), below row 1.
        assertThat(third.left).isEqualTo(first.left)
        assertThat(third.top).isGreaterThan(first.top)
        // Row 2 right + row 3 left continue the zig-zag despite the tall first card.
        assertThat(fourth.left).isEqualTo(second.left)
        assertThat(fourth.top).isEqualTo(third.top)
        assertThat(fifth.left).isEqualTo(first.left)
        assertThat(fifth.top).isGreaterThan(third.top)
    }
}
