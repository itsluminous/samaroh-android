package com.itsluminous.samaroh.core.i18n

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Codegen output audit (ADR-110): `shared/codegen/gen-android.mjs` must emit one resource
 * directory per catalog locale — `values/` (en), `values-hi/`, and the BCP-47 form
 * `values-b+hi+Latn/` for Hinglish (a script subtag has no two-letter qualifier; Android
 * rejects `values-hi-Latn`) — with the same resource names in each (non-translatable keys
 * ship only in `values/`), and Hinglish values stay Latin-script. The test reads the
 * generated tree, so it exercises the codegen this build ran, not a fixture.
 */
class GeneratedStringsTest {
    private val locales = CatalogTestSupport.locales()

    private fun generated(locale: String): File {
        val file = CatalogTestSupport.generatedStringsFile(locale)
        assertWithMessage("run ./gradlew generateStrings first: ${file.path}").that(file.exists()).isTrue()
        return file
    }

    private fun resourceNames(file: File): Set<String> =
        Regex("<(?:string|plurals) name=\"([a-z0-9_]+)\"").findAll(file.readText()).map { it.groupValues[1] }.toSet()

    /** Single-line `<string>` values by resource name (plurals are multi-line and skipped). */
    private fun stringValues(file: File): Map<String, String> =
        Regex("<string name=\"([a-z0-9_]+)\"[^>]*>(.*?)</string>")
            .findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun `codegen emits all three locales under their Android qualifiers`() {
        assertThat(locales).containsExactly("en", "hi", "hi-Latn")
        assertThat(CatalogTestSupport.valuesDirOf("en")).isEqualTo("values")
        assertThat(CatalogTestSupport.valuesDirOf("hi")).isEqualTo("values-hi")
        assertThat(CatalogTestSupport.valuesDirOf("hi-Latn")).isEqualTo("values-b+hi+Latn")
        for (locale in locales) assertThat(generated(locale).parentFile.name).isEqualTo(CatalogTestSupport.valuesDirOf(locale))
        // Nothing else was emitted (an invalid `values-hi-Latn/` would break resource merging).
        val resDir = generated("en").parentFile.parentFile
        assertThat(resDir.listFiles()!!.map(File::getName)).containsExactlyElementsIn(locales.map(CatalogTestSupport::valuesDirOf))
    }

    @Test
    fun `every locale generates the same resource names as en minus non-translatable`() {
        val en = resourceNames(generated("en"))
        val nonTranslatable = CatalogTestSupport.mergedNonTranslatableKeys("en").map { it.replace('.', '_') }.toSet()
        assertThat(nonTranslatable).isNotEmpty()
        for (locale in locales) {
            if (locale == "en") continue
            assertWithMessage("resource-name parity of $locale").that(resourceNames(generated(locale))).containsExactlyElementsIn(
                en - nonTranslatable,
            )
        }
    }

    @Test
    fun `generated values mirror the catalog per locale`() {
        // The Expenses tab: "Expenses" / "खर्च" / "Kharche" — one key, three scripts.
        val values = locales.associateWith { stringValues(generated(it)).getValue("common_nav_expenses") }
        assertThat(values["en"]).isEqualTo("Expenses")
        assertThat(values["hi"]).isEqualTo("खर्च")
        assertThat(values["hi-Latn"]).isEqualTo("Kharche")
        // Language names are own-script and locale-invariant (the picker shows them under any locale).
        for (locale in locales) {
            val strings = stringValues(generated(locale))
            assertThat(strings["settings_language_name_hi_latn"]).isEqualTo("Hinglish")
            assertThat(strings["common_language_hi_latn"]).isEqualTo("Hinglish")
            assertThat(strings["settings_language_name_hi"]).isEqualTo("हिन्दी")
        }
    }

    @Test
    fun `Hinglish values are Latin script unless identical to English`() {
        val en = stringValues(generated("en"))
        val devanagari = Regex("[\\u0900-\\u097F]")
        val offenders =
            stringValues(generated("hi-Latn"))
                .filter { (name, value) -> devanagari.containsMatchIn(value) && en[name] != value }
                .keys
        assertWithMessage("Devanagari in values-b+hi+Latn").that(offenders).isEmpty()
    }
}
