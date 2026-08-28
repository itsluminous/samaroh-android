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

    /**
     * The merged set of non-translatable keys of [locale] — entries marked
     * `"translatable": false`. They live only in the canonical (en) catalog; parity
     * deliberately excludes them (see gen-android.mjs / validate-catalogs.mjs).
     */
    fun mergedNonTranslatableKeys(locale: String): Set<String> {
        val keys = linkedSetOf<String>()
        for (file in catalogFilesFor(locale)) keys.addAll(nonTranslatableKeys(file))
        return keys
    }

    /** Extracts top-level JSON object keys without a JSON library (values never contain unescaped quote-brace pairs at depth 1). */
    fun topLevelKeys(file: File): Set<String> {
        val keys = linkedSetOf<String>()
        scanEntries(file) { key, _ -> keys.add(key) }
        return keys
    }

    /** Top-level keys of [file] whose entry object carries `"translatable": false`. */
    fun nonTranslatableKeys(file: File): Set<String> {
        val keys = linkedSetOf<String>()
        scanEntries(file) { key, nonTranslatable -> if (nonTranslatable) keys.add(key) }
        return keys
    }

    /**
     * Walks the file's top-level entries, reporting each key and whether its entry object
     * marks `"translatable": false`. Same depth-tracking scan as before — no JSON library.
     */
    private fun scanEntries(
        file: File,
        onEntry: (key: String, nonTranslatable: Boolean) -> Unit,
    ) {
        val text = file.readText()
        var depth = 0
        var i = 0
        var currentKey: String? = null
        var currentNonTranslatable = false

        fun flush() {
            currentKey?.let { onEntry(it, currentNonTranslatable) }
            currentKey = null
            currentNonTranslatable = false
        }
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> depth--
                '"' -> {
                    val start = i + 1
                    var j = start
                    while (j < text.length && (text[j] != '"' || text[j - 1] == '\\')) j++
                    val token = text.substring(start, j)
                    // A string followed (after whitespace) by ':' is a key at its depth.
                    var k = j + 1
                    while (k < text.length && text[k].isWhitespace()) k++
                    val isKey = k < text.length && text[k] == ':'
                    if (isKey && depth == 1) {
                        // A new top-level entry begins; report the previous one.
                        flush()
                        currentKey = token
                    }
                    if (isKey && depth == 2 && token == "translatable") {
                        var v = k + 1
                        while (v < text.length && text[v].isWhitespace()) v++
                        if (text.startsWith("false", v)) currentNonTranslatable = true
                    }
                    i = j
                    if (j >= text.length) break
                }
            }
            i++
        }
        flush()
    }

    /** Mirrors gen-android.mjs: catalog key `common.action.save` → resource name `common_action_save`. */
    fun androidResourceName(key: String): String = key.replace(Regex("""[.\-]"""), "_")
}
