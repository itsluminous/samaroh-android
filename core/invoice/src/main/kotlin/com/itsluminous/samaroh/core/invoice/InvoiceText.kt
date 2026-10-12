package com.itsluminous.samaroh.core.invoice

/** Pure text helpers for the invoice renderers (layout-spec §3a NOTES block). */
internal object InvoiceText {
    /**
     * Drops emoji and other pictographic glyphs (PDF fonts render them inconsistently,
     * ADR-035): every astral code point plus the BMP pictographs / variation selectors /
     * ZWJ; letters, digits, punctuation and Indic scripts are untouched.
     */
    fun stripEmoji(text: String): String =
        buildString(text.length) {
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val width = Character.charCount(cp)
                if (!isPictographic(cp)) appendCodePoint(cp)
                i += width
            }
        }

    private fun isPictographic(cp: Int): Boolean =
        cp > 0xFFFF ||
            // all astral planes (emoji live in U+1F000…)
            cp in 0x2600..0x27BF ||
            // misc symbols + dingbats (☀ ✂ ✅ …)
            cp in 0x2B00..0x2BFF ||
            // arrows/symbols block (⭐ …)
            cp == 0x200D ||
            // zero-width joiner
            cp in 0xFE00..0xFE0F // variation selectors (text/emoji presentation)

    /**
     * Greedy word wrap to [maxWidth] using [measure]; a single word wider than the line is
     * broken by characters so nothing is ever dropped (layout-spec §3a "never truncated").
     */
    fun wrap(
        paragraph: String,
        maxWidth: Float,
        measure: (String) -> Float,
    ): List<String> {
        val words = paragraph.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                lines += current.toString()
                current = StringBuilder()
            }
        }
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            when {
                measure(candidate) <= maxWidth -> current = StringBuilder(candidate)
                measure(word) <= maxWidth -> {
                    flush()
                    current = StringBuilder(word)
                }
                else -> {
                    // Over-long word: break it by characters.
                    flush()
                    var chunk = StringBuilder()
                    for (ch in word) {
                        if (measure(chunk.toString() + ch) > maxWidth && chunk.isNotEmpty()) {
                            lines += chunk.toString()
                            chunk = StringBuilder()
                        }
                        chunk.append(ch)
                    }
                    current = chunk
                }
            }
        }
        flush()
        return lines
    }
}
