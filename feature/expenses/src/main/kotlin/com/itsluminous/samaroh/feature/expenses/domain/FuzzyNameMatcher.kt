package com.itsluminous.samaroh.feature.expenses.domain

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Fuzzy name matcher behind the add-person dedupe dropdown (§4.2): while typing, existing
 * parties similar to the input are suggested so the user is steered to the existing person
 * instead of creating a duplicate. Combines exact/containment/prefix signals with
 * Levenshtein similarity (typo tolerance).
 */
object FuzzyNameMatcher {
    /** Suggestions appear from this many typed characters. */
    const val MIN_QUERY_LENGTH = 2

    /** Candidates scoring at or above this similarity are suggested. */
    const val DEFAULT_THRESHOLD = 0.4

    /** Case/whitespace-insensitive canonical form used for all comparisons. */
    fun normalize(name: String): String = name.trim().lowercase(Locale.ROOT).replace(WHITESPACE, " ")

    /** Similarity in [0, 1]: 1 = same name after normalization. */
    fun similarity(
        a: String,
        b: String,
    ): Double {
        val left = normalize(a)
        val right = normalize(b)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        if (left == right) return 1.0

        val lengthRatio = min(left.length, right.length).toDouble() / max(left.length, right.length)
        // A whole word of one name equalling the other (query "ramesh" vs "ramesh kumar")
        // outranks a partial-word prefix like "rameshwar".
        val tokenHit = left in right.split(' ') || right in left.split(' ')
        val token = if (tokenHit) TOKEN_FLOOR + (1 - TOKEN_FLOOR) * lengthRatio else 0.0
        val containment = if (left in right || right in left) CONTAINMENT_FLOOR + (1 - CONTAINMENT_FLOOR) * lengthRatio else 0.0
        val prefix = if (right.startsWith(left) || left.startsWith(right)) PREFIX_FLOOR + (1 - PREFIX_FLOOR) * lengthRatio else 0.0
        val edit = 1.0 - levenshtein(left, right).toDouble() / max(left.length, right.length)
        return maxOf(token, containment, prefix, edit)
    }

    /**
     * Candidates from [candidates] similar to [query], best first, capped at [limit].
     * Queries shorter than [MIN_QUERY_LENGTH] yield nothing.
     */
    fun rank(
        query: String,
        candidates: List<String>,
        threshold: Double = DEFAULT_THRESHOLD,
        limit: Int = 5,
    ): List<String> {
        if (normalize(query).length < MIN_QUERY_LENGTH) return emptyList()
        return candidates
            .map { it to similarity(query, it) }
            .filter { (_, score) -> score >= threshold }
            .sortedByDescending { (_, score) -> score }
            .take(limit)
            .map { (candidate, _) -> candidate }
    }

    private fun levenshtein(
        a: String,
        b: String,
    ): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private val WHITESPACE = Regex("\\s+")
    private const val TOKEN_FLOOR = 0.9
    private const val CONTAINMENT_FLOOR = 0.6
    private const val PREFIX_FLOOR = 0.7
}
