package com.itsluminous.samaroh.feature.inventory

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.feature.inventory.domain.FuzzyMatcher
import org.junit.Test

class FuzzyMatcherTest {
    private data class Named(
        val name: String,
    )

    private val items =
        listOf(
            Named("Steel Plate"),
            Named("Steel Glass"),
            Named("Plastic Chair"),
            Named("Table Cloth"),
            Named("Gas Cylinder"),
        )

    private fun search(
        query: String,
        threshold: Double = FuzzyMatcher.DUPLICATE_THRESHOLD,
        excludeExact: Boolean = true,
    ) = FuzzyMatcher.findSimilar(query, items, { it.name }, threshold = threshold, excludeExactMatch = excludeExact)

    @Test
    fun `levenshtein distance matches known values`() {
        assertThat(FuzzyMatcher.levenshtein("kitten", "sitting")).isEqualTo(3)
        assertThat(FuzzyMatcher.levenshtein("", "abc")).isEqualTo(3)
        assertThat(FuzzyMatcher.levenshtein("same", "same")).isEqualTo(0)
    }

    @Test
    fun `similarity is 1 for identical and proportional otherwise`() {
        assertThat(FuzzyMatcher.similarity("plate", "plate")).isEqualTo(1.0)
        assertThat(FuzzyMatcher.similarity("plate", "plates")).isWithin(1e-9).of(5.0 / 6.0)
    }

    @Test
    fun `queries below three characters return nothing`() {
        assertThat(search("st")).isEmpty()
        assertThat(search("  s ")).isEmpty()
    }

    @Test
    fun `prefix match scores highest and sorts first`() {
        val results = search("Steel")
        assertThat(results).isNotEmpty()
        assertThat(results.first().score).isAtLeast(0.9)
        assertThat(results.map { it.item.name }).containsAtLeast("Steel Plate", "Steel Glass")
    }

    @Test
    fun `substring match scores at least point-eight`() {
        val results = search("Cylin")
        assertThat(results.map { it.item.name }).contains("Gas Cylinder")
        assertThat(results.first { it.item.name == "Gas Cylinder" }.score).isAtLeast(0.8)
    }

    @Test
    fun `swapped word order still matches via normalization`() {
        val results = search("Plate Steel")
        assertThat(results.map { it.item.name }).contains("Steel Plate")
    }

    @Test
    fun `typo within a word still matches at duplicate threshold`() {
        val results = search("Steal Plate")
        assertThat(results.map { it.item.name }).contains("Steel Plate")
    }

    @Test
    fun `exact match is skipped for duplicate detection but kept for the picker`() {
        val duplicates = search("Steel Plate")
        assertThat(duplicates.map { it.item.name }).doesNotContain("Steel Plate")

        val pickerResults = search("Steel Plate", excludeExact = false)
        assertThat(pickerResults.first().item.name).isEqualTo("Steel Plate")
        assertThat(pickerResults.first().score).isEqualTo(1.0)
    }

    @Test
    fun `unrelated names stay below the duplicate threshold`() {
        val results = search("Steel")
        assertThat(results.map { it.item.name }).doesNotContain("Table Cloth")
    }

    @Test
    fun `results are capped at max results`() {
        val many = (1..10).map { Named("Steel Item $it") }
        val results = FuzzyMatcher.findSimilar("Steel", many, { it.name })
        assertThat(results).hasSize(FuzzyMatcher.DEFAULT_MAX_RESULTS)
    }
}
