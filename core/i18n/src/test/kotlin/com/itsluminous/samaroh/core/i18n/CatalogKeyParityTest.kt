package com.itsluminous.samaroh.core.i18n

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Walks every `shared/strings/catalog.<locale>.json` and asserts that all locales expose
 * exactly the same key set (spec §5: no missing translations, ever). The catalog files
 * are intentionally simple `key -> { value, description }` JSON, so a dependency-free
 * scanner is enough to extract the top-level keys.
 */
class CatalogKeyParityTest {
    private val stringsDir = File(requireNotNull(System.getProperty("samaroh.sharedStringsDir")) { "samaroh.sharedStringsDir not set" })

    private fun catalogFiles(): List<File> =
        stringsDir
            .listFiles { f -> f.name.matches(Regex("""catalog\.[a-z]{2}(-[A-Za-z]+)?\.json""")) }
            .orEmpty()
            .sortedBy(File::getName)

    /** Extracts top-level JSON object keys without a JSON library (values never contain unescaped quotes-brace pairs at depth 1). */
    private fun topLevelKeys(file: File): Set<String> {
        val keys = linkedSetOf<String>()
        val text = file.readText()
        var depth = 0
        var i = 0
        while (i < text.length) {
            when (val c = text[i]) {
                '{' -> depth++
                '}' -> depth--
                '"' -> {
                    val start = i + 1
                    var j = start
                    while (j < text.length && (text[j] != '"' || text[j - 1] == '\\')) j++
                    val token = text.substring(start, j)
                    // A depth-1 string followed (after whitespace) by ':' is a key.
                    var k = j + 1
                    while (k < text.length && text[k].isWhitespace()) k++
                    if (depth == 1 && k < text.length && text[k] == ':') keys.add(token)
                    i = j
                    if (c == '"' && j >= text.length) return keys
                }
            }
            i++
        }
        return keys
    }

    @Test
    fun `at least english and hindi catalogs exist`() {
        val names = catalogFiles().map(File::getName)
        assertThat(names).containsAtLeast("catalog.en.json", "catalog.hi.json")
    }

    @Test
    fun `all locales have identical key sets`() {
        val files = catalogFiles()
        assertThat(files).isNotEmpty()
        val canonical = files.first { it.name == "catalog.en.json" }
        val canonicalKeys = topLevelKeys(canonical)
        assertThat(canonicalKeys).isNotEmpty()
        for (file in files) {
            if (file == canonical) continue
            val keys = topLevelKeys(file)
            assertWithMessage("keys missing in ${file.name}").that(keys).containsAtLeastElementsIn(canonicalKeys)
            assertWithMessage("extra keys in ${file.name}").that(canonicalKeys).containsAtLeastElementsIn(keys)
        }
    }

    @Test
    fun `keys follow the module_screen_element convention`() {
        val keys = topLevelKeys(catalogFiles().first { it.name == "catalog.en.json" })
        for (key in keys) {
            assertWithMessage("key '$key' must be dot-namespaced lowercase")
                .that(key.matches(Regex("""[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+""")))
                .isTrue()
        }
    }
}
