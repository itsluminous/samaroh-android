package com.itsluminous.samaroh.core.help

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

/** App locale → guide language (ADR-108 §3, ADR-110): script decides Hinglish, region never does. */
class HelpLanguageTest {
    @Test
    fun `Latin-script Hindi is hi-Latn`() {
        assertThat(HelpLanguage.of(Locale.forLanguageTag("hi-Latn"))).isEqualTo("hi-Latn")
        assertThat(HelpLanguage.of(Locale.forLanguageTag("hi-Latn-IN"))).isEqualTo("hi-Latn")
    }

    @Test
    fun `other Hindi locales are hi`() {
        assertThat(HelpLanguage.of(Locale.forLanguageTag("hi"))).isEqualTo("hi")
        assertThat(HelpLanguage.of(Locale.forLanguageTag("hi-IN"))).isEqualTo("hi")
        assertThat(HelpLanguage.of(Locale.forLanguageTag("hi-Deva"))).isEqualTo("hi")
    }

    @Test
    fun `everything else falls back to en`() {
        for (tag in listOf("en", "en-IN", "en-US", "mr", "ur-Latn")) {
            assertThat(HelpLanguage.of(Locale.forLanguageTag(tag))).isEqualTo("en")
        }
    }

    @Test
    fun `ALL lists the guide languages in site order`() {
        assertThat(HelpLanguage.ALL).containsExactly("en", "hi", "hi-Latn").inOrder()
    }
}
