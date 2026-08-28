package com.itsluminous.samaroh.core.i18n

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Walks every catalog source — the base `catalog.<locale>.json` PLUS every
 * `<name>.<locale>.json` under `fragments` (exactly the set gen-android.mjs merges) —
 * and asserts that all locales expose the same merged key set (spec §5: no missing
 * translations, ever — entries marked `"translatable": false` are en-only data values
 * exempt from parity and banned from translated locales), that no key is defined twice
 * across files (a codegen hard error), and that keys follow the naming convention.
 */
class CatalogKeyParityTest {
    @Test
    fun `at least english and hindi catalogs exist`() {
        assertThat(CatalogTestSupport.locales()).containsAtLeast("en", "hi")
    }

    @Test
    fun `every locale has a counterpart for every catalog fragment`() {
        val canonical = CatalogTestSupport.catalogFilesFor("en").map(File::getName)
        for (locale in CatalogTestSupport.locales()) {
            if (locale == "en") continue
            val localized = CatalogTestSupport.catalogFilesFor(locale).map { it.name.replace(".$locale.json", ".en.json") }
            assertWithMessage("catalog files of '$locale' must mirror English").that(localized).isEqualTo(canonical)
        }
    }

    @Test
    fun `no key is defined twice across catalog files`() {
        for (locale in CatalogTestSupport.locales()) {
            val duplicates = mutableListOf<String>()
            CatalogTestSupport.mergedKeys(locale) { key, file -> duplicates.add("$key (again in ${file.name})") }
            assertWithMessage("duplicate keys in locale '$locale'").that(duplicates).isEmpty()
        }
    }

    @Test
    fun `all locales have identical merged key sets`() {
        val canonicalKeys = CatalogTestSupport.mergedKeys("en")
        // Non-translatable (data) entries are en-only by contract: excluded from parity,
        // and their presence in a translated locale is an error (validated below).
        val nonTranslatable = CatalogTestSupport.mergedNonTranslatableKeys("en")
        val translatableKeys = canonicalKeys - nonTranslatable
        assertThat(translatableKeys).isNotEmpty()
        for (locale in CatalogTestSupport.locales()) {
            if (locale == "en") continue
            val keys = CatalogTestSupport.mergedKeys(locale)
            assertWithMessage("keys missing in locale '$locale'").that(keys).containsAtLeastElementsIn(translatableKeys)
            assertWithMessage("extra keys in locale '$locale'").that(translatableKeys).containsAtLeastElementsIn(keys)
        }
    }

    @Test
    fun `non-translatable keys exist only in the canonical locale`() {
        val nonTranslatable = CatalogTestSupport.mergedNonTranslatableKeys("en")
        for (locale in CatalogTestSupport.locales()) {
            if (locale == "en") continue
            val keys = CatalogTestSupport.mergedKeys(locale)
            val leaked = nonTranslatable.filter { it in keys }
            assertWithMessage("non-translatable (en-only) keys must have no entry in '$locale'").that(leaked).isEmpty()
        }
    }

    @Test
    fun `keys follow the module_screen_element convention`() {
        for (key in CatalogTestSupport.mergedKeys("en")) {
            assertWithMessage("key '$key' must be dot-namespaced lowercase")
                .that(key.matches(Regex("""[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+""")))
                .isTrue()
        }
    }
}
