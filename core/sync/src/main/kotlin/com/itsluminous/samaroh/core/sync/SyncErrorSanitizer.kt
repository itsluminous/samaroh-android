package com.itsluminous.samaroh.core.sync

/**
 * Sanitizes raw sync error detail before it is surfaced in the UI (§4.4 sync-status
 * screen). Raw HTTP failures can echo request headers — including the Supabase anon
 * key — so anything that looks like a credential header or token is stripped, and the
 * remainder is truncated to a UI-friendly length. The full raw detail stays in the
 * outbox entity's `lastError` column for diagnostics; only the displayed copy is cleaned.
 */
object SyncErrorSanitizer {
    private const val MAX_DISPLAY_LENGTH = 200
    private const val ELLIPSIS = "\u2026"

    /**
     * Inline `key=value` / `key: value` credential pairs (covers whole header lines like
     * `apikey: <jwt>`) and bearer tokens.
     */
    private val sensitiveInline =
        Regex(
            "(?i)\\b(apikey|api[-_ ]?key|authorization|x-api-key|anon[-_ ]?key|" +
                "access[-_ ]?token|refresh[-_ ]?token|client[-_ ]?secret)\\b\\s*[:=]?\\s*(bearer\\s+)?\\S*" +
                "|\\bbearer\\s+\\S+",
        )

    /** Anything shaped like a JWT (three base64url segments) — the anon key is one. */
    private val jwtLike = Regex("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b")

    fun sanitize(raw: String): String {
        val cleaned =
            raw
                .replace(sensitiveInline, "")
                .replace(jwtLike, "")
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
                .replace(Regex("\\s{2,}"), " ")
                .trim()
        return if (cleaned.length <= MAX_DISPLAY_LENGTH) cleaned else cleaned.take(MAX_DISPLAY_LENGTH).trimEnd() + ELLIPSIS
    }
}
