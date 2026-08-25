package com.itsluminous.samaroh.feature.expenses.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FuzzyNameMatcherTest {
    @Test
    fun `identical names after normalization score 1`() {
        assertThat(FuzzyNameMatcher.similarity("  Ram   Kumar ", "ram kumar")).isEqualTo(1.0)
    }

    @Test
    fun `empty input scores 0`() {
        assertThat(FuzzyNameMatcher.similarity("", "ram")).isEqualTo(0.0)
        assertThat(FuzzyNameMatcher.similarity("ram", "   ")).isEqualTo(0.0)
    }

    @Test
    fun `typo within a name still scores above threshold`() {
        assertThat(FuzzyNameMatcher.similarity("Ramesh", "Rameshh")).isAtLeast(FuzzyNameMatcher.DEFAULT_THRESHOLD)
        assertThat(FuzzyNameMatcher.similarity("Suresh Traders", "Sursh Traders")).isAtLeast(FuzzyNameMatcher.DEFAULT_THRESHOLD)
    }

    @Test
    fun `prefix of an existing name matches`() {
        assertThat(FuzzyNameMatcher.similarity("Ram", "Ramesh Kumar")).isAtLeast(FuzzyNameMatcher.DEFAULT_THRESHOLD)
    }

    @Test
    fun `unrelated names stay below threshold`() {
        assertThat(FuzzyNameMatcher.similarity("Ramesh", "Priya Caterers")).isLessThan(FuzzyNameMatcher.DEFAULT_THRESHOLD)
    }

    @Test
    fun `rank orders best match first and caps results`() {
        val candidates = listOf("Ramesh Kumar", "Ram Lal", "Priya Caterers", "Rameshwar", "Raman", "Ramu")
        val ranked = FuzzyNameMatcher.rank("Ramesh", candidates, limit = 3)
        assertThat(ranked).hasSize(3)
        assertThat(ranked.first()).isEqualTo("Ramesh Kumar")
        assertThat(ranked).doesNotContain("Priya Caterers")
    }

    @Test
    fun `rank returns nothing for queries shorter than the minimum`() {
        assertThat(FuzzyNameMatcher.rank("r", listOf("Ram", "Ramesh"))).isEmpty()
    }

    @Test
    fun `rank filters below-threshold candidates`() {
        val ranked = FuzzyNameMatcher.rank("Ramesh", listOf("Priya Caterers"))
        assertThat(ranked).isEmpty()
    }

    @Test
    fun `devanagari names match case-insensitively`() {
        assertThat(FuzzyNameMatcher.similarity("रमेश", "रमेश")).isEqualTo(1.0)
        assertThat(FuzzyNameMatcher.similarity("रमेश", "रमेश कुमार")).isAtLeast(FuzzyNameMatcher.DEFAULT_THRESHOLD)
    }
}
