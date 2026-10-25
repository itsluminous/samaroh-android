package com.itsluminous.samaroh.core.i18n

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The pure-JVM half of [LocaleManager]: tag matching and picker labels (ADR-110). */
class LocaleManagerTest {
    @Test
    fun `ships three locales in picker order`() {
        assertThat(LocaleManager.supportedLocales).containsExactly("en", "hi", "hi-Latn").inOrder()
    }

    @Test
    fun `supportedTagOf matches language AND script, never by prefix`() {
        assertThat(LocaleManager.supportedTagOf("hi-Latn")).isEqualTo("hi-Latn")
        assertThat(LocaleManager.supportedTagOf("hi")).isEqualTo("hi")
        assertThat(LocaleManager.supportedTagOf("en")).isEqualTo("en")
        // Region subtags the framework may append are ignored; a locale LIST takes its first entry.
        assertThat(LocaleManager.supportedTagOf("hi-Latn-IN")).isEqualTo("hi-Latn")
        assertThat(LocaleManager.supportedTagOf("en-US")).isEqualTo("en")
        assertThat(LocaleManager.supportedTagOf("hi-IN,en-US")).isEqualTo("hi")
        // The bug the picker had: "hi-Latn".startsWith("hi") ticked the Hindi row.
        assertThat(LocaleManager.supportedTagOf("hi-Latn")).isNotEqualTo("hi")
        assertThat(LocaleManager.supportedTagOf("hi")).isNotEqualTo("hi-Latn")
    }

    @Test
    fun `supportedTagOf is null for system-following and unknown tags`() {
        assertThat(LocaleManager.supportedTagOf(null)).isNull()
        assertThat(LocaleManager.supportedTagOf("")).isNull()
        assertThat(LocaleManager.supportedTagOf("mr")).isNull()
        assertThat(LocaleManager.supportedTagOf("hi-Deva")).isNull()
    }

    @Test
    fun `every supported tag has its own picker labels`() {
        val settings = LocaleManager.supportedLocales.map(LocaleManager::settingsNameRes)
        val common = LocaleManager.supportedLocales.map(LocaleManager::commonNameRes)
        assertThat(settings).containsNoDuplicates()
        assertThat(common).containsNoDuplicates()
        assertThat(LocaleManager.settingsNameRes("hi-Latn")).isEqualTo(R.string.settings_language_name_hi_latn)
        assertThat(LocaleManager.commonNameRes("hi-Latn")).isEqualTo(R.string.common_language_hi_latn)
    }
}
