package com.itsluminous.samaroh.core.i18n

import java.io.File

/** Shared helpers for the catalog unit tests: file discovery + a dependency-free key scanner. */
internal object CatalogTestSupport {
    fun stringsDir(): File = File(requireNotNull(System.getProperty("samaroh.sharedStringsDir")) { "samaroh.sharedStringsDir not set" })

    fun repoRootDir(): File = File(requireNotNull(System.getProperty("samaroh.repoRootDir")) { "samaroh.repoRootDir not set" })

    private val localePattern = Regex("""[a-z]{2}(-[A-Za-z]+)?""")

    /** Locales shipped in the catalog, discovered from the base `catalog.<locale>.json` files. */
    fun locales(): List<String> =
        stringsDir()
            .listFiles { f -> f.name.matches(Regex("""catalog\.${localePattern.pattern}\.json""")) }
            .orEmpty()
            .map { it.name.removePrefix("catalog.").removeSuffix(".json") }
            .sorted()

    /**
     * Every catalog source of [locale], mirroring gen-android.mjs: the base
     * `catalog.<locale>.json` merged with each `<name>.<locale>.json` under `fragments`.
     */
    fun catalogFilesFor(locale: String): List<File> {
        val base = File(stringsDir(), "catalog.$locale.json")
        val fragments =
            File(stringsDir(), "fragments")
                .listFiles { f -> f.name.endsWith(".$locale.json") }
                .orEmpty()
                .sortedBy(File::getName)
        return listOf(base) + fragments
    }

    /**
     * The merged key set of [locale]. Duplicate keys across files are a codegen hard
     * error (gen-android.mjs), so the merge asserts uniqueness too via [onDuplicate].
     */
    fun mergedKeys(
        locale: String,
        onDuplicate: (key: String, file: File) -> Unit = { _, _ -> },
    ): Set<String> {
        val keys = linkedSetOf<String>()
        for (file in catalogFilesFor(locale)) {
            for (key in topLevelKeys(file)) {
                if (!keys.add(key)) onDuplicate(key, file)
            }
        }
        return keys
    }

    /** Extracts top-level JSON object keys without a JSON library (values never contain unescaped quote-brace pairs at depth 1). */
    fun topLevelKeys(file: File): Set<String> {
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

    /** Mirrors gen-android.mjs: catalog key `common.action.save` → resource name `common_action_save`. */
    fun androidResourceName(key: String): String = key.replace(Regex("""[.\-]"""), "_")
}
