package com.itsluminous.samaroh.core.invoice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InvoiceTextTest {
    @Test
    fun `stripEmoji drops pictographs and keeps text in every script`() {
        assertThat(InvoiceText.stripEmoji("Stage by 4 pm \uD83C\uDF89 \u2705 ok")).isEqualTo("Stage by 4 pm   ok")
        assertThat(InvoiceText.stripEmoji("मंच 4 बजे तक")).isEqualTo("मंच 4 बजे तक")
        // ZWJ sequences and variation selectors vanish entirely.
        assertThat(InvoiceText.stripEmoji("a\uD83D\uDC68\u200D\uD83D\uDCBB\uFE0Fb")).isEqualTo("ab")
        assertThat(InvoiceText.stripEmoji("line1\nline2")).isEqualTo("line1\nline2")
    }

    @Test
    fun `wrap breaks on words and never drops text`() {
        val measure = { s: String -> s.length.toFloat() }
        assertThat(InvoiceText.wrap("the quick brown fox jumps", 10f, measure))
            .containsExactly("the quick", "brown fox", "jumps")
            .inOrder()
        // A word wider than the line is split by characters instead of being truncated.
        assertThat(InvoiceText.wrap("abcdefghijkl mn", 5f, measure))
            .containsExactly("abcde", "fghij", "kl mn")
            .inOrder()
        assertThat(InvoiceText.wrap("   ", 5f, measure)).isEmpty()
    }
}
