package com.itsluminous.samaroh.core.i18n

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Release-checklist guard (ADR-108, AGENTS.md "guide and UI ship together"): every catalog
 * key a `shared/help/guide.json` step cites in `keys` must exist in the GENERATED Android
 * string resources of every locale — i.e. the label the guide tells the user to tap is a
 * label this build actually renders. A key renamed or removed in the catalog without
 * updating the guide fails here, next to the other catalog audits.
 */
class HelpGuideCatalogKeysTest {
    private val guide: File = File(CatalogTestSupport.stringsDir().parentFile, "help/guide.json")

    private fun generatedStringNames(valuesDir: String): Set<String> {
        val file = File(CatalogTestSupport.repoRootDir(), "core/i18n/build/generated/sharedStrings/res/$valuesDir/strings.xml")
        assertWithMessage("run ./gradlew generateStrings first: ${file.path}").that(file.exists()).isTrue()
        return Regex("<(?:string|plurals) name=\"([a-z0-9_]+)\"").findAll(file.readText()).map { it.groupValues[1] }.toSet()
    }

    /** The guide's `keys` arrays, flattened (the file is small; no JSON library in this module). */
    private fun guideKeys(): Set<String> =
        Regex("\"keys\"\\s*:\\s*\\[([^]]*)]")
            .findAll(guide.readText())
            .flatMap { match -> Regex("\"([a-z0-9_.]+)\"").findAll(match.groupValues[1]).map { it.groupValues[1] } }
            .toSet()

    @Test
    fun `guide exists next to the string catalogs`() {
        assertThat(guide.exists()).isTrue()
        assertThat(guideKeys()).isNotEmpty()
    }

    @Test
    fun `every guide key resolves to a generated string in every locale`() {
        val keys = guideKeys()
        val missing = mutableListOf<String>()
        for (valuesDir in CatalogTestSupport.locales().map(CatalogTestSupport::valuesDirOf)) {
            val names = generatedStringNames(valuesDir)
            // Non-translatable keys are emitted once in the default values/ only.
            val nonTranslatable = CatalogTestSupport.mergedNonTranslatableKeys("en")
            for (key in keys) {
                val name = key.replace('.', '_')
                if (name !in names && !(valuesDir != "values" && key in nonTranslatable)) {
                    missing.add("$key → $name missing in $valuesDir")
                }
            }
        }
        assertWithMessage("guide.json cites catalog keys this build does not generate").that(missing).isEmpty()
    }

    @Test
    fun `every guide key is a translatable catalog key of every locale`() {
        val keys = guideKeys()
        val locales = CatalogTestSupport.locales()
        assertThat(locales).containsAtLeast("en", "hi", "hi-Latn")
        for (locale in locales) {
            assertWithMessage("not in $locale catalog").that(CatalogTestSupport.mergedKeys(locale)).containsAtLeastElementsIn(keys)
        }
    }
}
