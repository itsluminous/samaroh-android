package com.itsluminous.samaroh.core.i18n

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Catalog usage audit (spec §11 W2-B "catalog key-parity audit"):
 *
 * 1. **Missing keys FAIL**: every `R.string.x` / `R.plurals.x` reference in Kotlin
 *    source (the generated i18n resources are the only string resources feature code
 *    may use) must map back to a key in `catalog.en.json`. A reference to a resource
 *    the catalog does not generate is a broken build waiting for `generateStrings`.
 * 2. **Unused keys WARN**: catalog keys never referenced from Android code are printed
 *    as warnings, not failures — keys may be consumed by the web client (shared
 *    catalog, spec §1.3) or looked up dynamically.
 *
 * Dynamic-key false positives: a key resolved at runtime (for example via
 * `Resources.getIdentifier` or a name built from wire values) is invisible to this
 * static scan. If such a lookup is ever introduced, add its keys (or a prefix) to
 * [DYNAMIC_KEY_ALLOWLIST] so the unused-key warning stays meaningful; the missing-key
 * check is unaffected (dynamic lookups never produce compile-time `R.string` tokens).
 */
class CatalogUsageAuditTest {
    /** Key prefixes that are resolved dynamically or intentionally web/Android-shared. */
    private val dynamicKeyAllowlist: List<Regex> = DYNAMIC_KEY_ALLOWLIST

    /** Module source roots to scan (the shared submodule and build outputs are excluded). */
    private fun kotlinSources(): Sequence<File> {
        val root = CatalogTestSupport.repoRootDir()
        return sequenceOf("app", "core", "feature")
            .map { File(root, it) }
            .filter(File::isDirectory)
            .flatMap { dir -> dir.walkTopDown().onEnter { it.name != "build" }.asSequence() }
            .filter { it.isFile && it.extension == "kt" }
    }

    /** `R.string`/`R.plurals` tokens, excluding `android.R.*` via the lookbehind; FQN i18n references included. */
    private fun referencedResourceNames(): Set<String> {
        val plain = Regex("""(?<![\w.])R\.(?:string|plurals)\.([A-Za-z0-9_]+)""")
        val qualified = Regex("""core\.i18n\.R\.(?:string|plurals)\.([A-Za-z0-9_]+)""")
        val blockComment = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val lineComment = Regex("""//.*""")
        val names = sortedSetOf<String>()
        for (file in kotlinSources()) {
            // Comments are stripped so KDoc examples (like the ones in this file) don't count.
            val text = file.readText().replace(blockComment, "").replace(lineComment, "")
            plain.findAll(text).forEach { names.add(it.groupValues[1]) }
            qualified.findAll(text).forEach { names.add(it.groupValues[1]) }
        }
        return names
    }

    private fun catalogResourceNames(): Set<String> =
        CatalogTestSupport
            .mergedKeys("en")
            .map(CatalogTestSupport::androidResourceName)
            .toSet()

    @Test
    fun `every string resource referenced in code exists in the catalog`() {
        val missing = referencedResourceNames() - catalogResourceNames()
        assertWithMessage(
            "Kotlin source references string resources with no catalog key " +
                "(add the key to shared/strings/catalog.en.json AND catalog.hi.json, " +
                "bump the submodule, re-run generateStrings)",
        ).that(missing).isEmpty()
    }

    @Test
    fun `unused catalog keys are reported as warnings`() {
        val referenced = referencedResourceNames()
        val unused =
            catalogResourceNames()
                .filterNot { it in referenced }
                .filterNot { name -> dynamicKeyAllowlist.any { it.matches(name) } }
                .sorted()
        if (unused.isNotEmpty()) {
            println("WARNING: ${unused.size} catalog key(s) unreferenced from Android code (may be web-only):")
            unused.forEach { println("  - $it") }
        }
        // Intentionally never fails (see class KDoc) — the report keeps the catalog honest.
    }

    private companion object {
        val DYNAMIC_KEY_ALLOWLIST: List<Regex> = emptyList()
    }
}
