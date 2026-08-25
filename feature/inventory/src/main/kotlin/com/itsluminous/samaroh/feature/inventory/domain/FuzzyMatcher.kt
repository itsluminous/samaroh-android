package com.itsluminous.samaroh.feature.inventory.domain

import kotlin.math.max
import kotlin.math.min

/**
 * Multi-algorithm fuzzy name matcher for master items (spec §4.3): powers both the
 * duplicate-suggestion chips in the item editor (threshold 0.4 after 3+ characters) and
 * the type-ahead item picker in the record-transaction dialog.
 *
 * A candidate's score is the best of several signals:
 * 1. starts-with match (0.9) and substring match (0.8);
 * 2. whole-string Levenshtein similarity blended with word-level similarity;
 * 3. word-order-normalized Levenshtein similarity (handles swapped words);
 * 4. pure word-level similarity (each query word vs its closest target word).
 */
object FuzzyMatcher {
    /** Similarity floor for the duplicate-suggestion chips (40%). */
    const val DUPLICATE_THRESHOLD = 0.4

    /** Minimum typed characters before any suggestions appear. */
    const val MIN_QUERY_LENGTH = 3

    const val DEFAULT_MAX_RESULTS = 5

    data class Match<T>(
        val item: T,
        val score: Double,
    )

    /**
     * Scores [items] against [query] and returns those at or above [threshold], best
     * first, capped at [maxResults]. Exact (case-insensitive) matches are skipped when
     * [excludeExactMatch] — the duplicate detector must not flag the item itself —
     * and scored 1.0 when included (the picker wants them on top).
     */
    fun <T> findSimilar(
        query: String,
        items: List<T>,
        nameOf: (T) -> String,
        threshold: Double = DUPLICATE_THRESHOLD,
        maxResults: Int = DEFAULT_MAX_RESULTS,
        excludeExactMatch: Boolean = true,
    ): List<Match<T>> {
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY_LENGTH) return emptyList()

        val results = mutableListOf<Match<T>>()
        for (item in items) {
            val name = nameOf(item)
            if (trimmed.equals(name.trim(), ignoreCase = true)) {
                if (!excludeExactMatch) results += Match(item, 1.0)
                continue
            }
            val score = combinedScore(trimmed, name)
            if (score >= threshold) results += Match(item, score)
        }
        return results.sortedByDescending { it.score }.take(maxResults)
    }

    private fun combinedScore(
        query: String,
        target: String,
    ): Double {
        val queryLower = query.lowercase().trim()
        val targetLower = target.lowercase().trim()

        val directSimilarity = similarity(queryLower, targetLower)
        val substringMatch = if (targetLower.contains(queryLower)) 0.8 else 0.0
        val startsWithMatch = if (targetLower.startsWith(queryLower)) 0.9 else 0.0
        val wordScore = wordSimilarity(query, target)
        val normalizedSimilarity = similarity(normalize(query), normalize(target))

        return maxOf(
            startsWithMatch,
            substringMatch,
            directSimilarity * 0.6 + wordScore * 0.4,
            normalizedSimilarity * 0.7,
            wordScore,
        )
    }

    /** Levenshtein edit distance between two strings. */
    internal fun levenshtein(
        a: String,
        b: String,
    ): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(a.length + 1) { it }
        val current = IntArray(a.length + 1)
        for (j in 1..b.length) {
            current[0] = j
            for (i in 1..a.length) {
                val substitution = previous[i - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[i] = min(min(current[i - 1] + 1, previous[i] + 1), substitution)
            }
            previous = current.copyOf()
        }
        return previous[a.length]
    }

    /** Levenshtein similarity in 0..1 (1 = identical). */
    internal fun similarity(
        a: String,
        b: String,
    ): Double {
        val maxLength = max(a.length, b.length)
        if (maxLength == 0) return 1.0
        return (maxLength - levenshtein(a, b)).toDouble() / maxLength
    }

    /** Lowercase, collapse whitespace and sort words — makes word order irrelevant. */
    private fun normalize(value: String): String =
        value
            .lowercase()
            .trim()
            .split(Regex("\\s+"))
            .sorted()
            .joinToString(" ")

    private fun words(value: String): List<String> =
        value
            .lowercase()
            .trim()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), "")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }

    /** Each query word scored against its closest target word; > 0.7 counts as a hit. */
    private fun wordSimilarity(
        query: String,
        target: String,
    ): Double {
        val queryWords = words(query)
        val targetWords = words(target)
        if (queryWords.isEmpty() || targetWords.isEmpty()) return 0.0

        var totalMatches = 0.0
        for (queryWord in queryWords) {
            val best = targetWords.maxOf { similarity(queryWord, it) }
            if (best > 0.7) totalMatches += best
        }
        return totalMatches / max(queryWords.size, targetWords.size)
    }
}
