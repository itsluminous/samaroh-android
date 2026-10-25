package com.itsluminous.samaroh.core.i18n

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class FormatLocaleTest {
    private val date = LocalDate.of(2026, 10, 25)

    private fun medium(locale: Locale) = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

    @Test
    fun `Hinglish formats as en-IN`() {
        assertThat(FormatLocale.of(Locale.forLanguageTag("hi-Latn"))).isEqualTo(Locale("en", "IN"))
        assertThat(FormatLocale.of(Locale.forLanguageTag("hi-Latn-IN"))).isEqualTo(Locale("en", "IN"))
        assertThat(FormatLocale.isHinglish(Locale.forLanguageTag("hi-Latn"))).isTrue()
    }

    @Test
    fun `other locales format as themselves`() {
        for (tag in listOf("en", "en-US", "hi", "hi-IN", "mr")) {
            val locale = Locale.forLanguageTag(tag)
            assertThat(FormatLocale.of(locale)).isEqualTo(locale)
            assertThat(FormatLocale.isHinglish(locale)).isFalse()
        }
    }

    @Test
    fun `null falls back to the process default`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("hi-Latn"))
            assertThat(FormatLocale.of(null)).isEqualTo(Locale("en", "IN"))
            assertThat(FormatLocale.default()).isEqualTo(Locale("en", "IN"))
            Locale.setDefault(Locale.forLanguageTag("hi"))
            assertThat(FormatLocale.default()).isEqualTo(Locale.forLanguageTag("hi"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `a Hinglish date renders with Latin digits and English month names, Indian order`() {
        val hinglish = medium(FormatLocale.of(Locale.forLanguageTag("hi-Latn")))
        // Exact punctuation is CLDR's business (`25-Oct-2026` on OpenJDK, `25 Oct 2026` on Android);
        // the contract is Latin digits + English month, day before month, no Devanagari.
        assertThat(hinglish).containsMatch("^25\\W+Oct\\W+2026$")
        assertThat(hinglish).doesNotContainMatch("[\\u0900-\\u097F]")
        // The unmapped tag is what we are avoiding: on a JVM/ICU without hi_Latn data it falls back to hi.
        assertThat(medium(Locale.forLanguageTag("hi"))).containsMatch("[\\u0900-\\u097F]")
    }
}
