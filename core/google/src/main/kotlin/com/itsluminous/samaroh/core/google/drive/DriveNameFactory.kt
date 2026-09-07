package com.itsluminous.samaroh.core.google.drive

import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Human-readable Drive file names (ADR-058): `{base}-{yyyyMMdd-HHmmss}{ext}`, where the
 * base is a sanitized item/party name. Drive itself tolerates duplicate names (they get
 * distinct file ids), but two identical names uploaded in the same second would be
 * indistinguishable to a human browsing the folder — so names issued within one
 * timestamp second are uniquified with a `-2`, `-3`… suffix. The set resets when the
 * second rolls over: memory stays O(uploads-in-one-second), never grows unbounded.
 *
 * The timestamp renders in the DEVICE zone (the owner's wall clock — the whole point is
 * a name a human recognizes), while the instant comes from the injected [Clock] so tests
 * stay deterministic.
 */
@Singleton
class DriveNameFactory internal constructor(
    private val clock: Clock,
    private val zone: ZoneId,
) {
    @Inject
    constructor(clock: Clock) : this(clock, ZoneId.systemDefault())

    private val formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(zone)

    private var currentStamp: String? = null
    private val issued = mutableSetOf<String>()

    /**
     * Builds the next Drive name for [rawBase] with [extension] (leading dot, e.g.
     * `.webp`). [fallbackBase] replaces a base that sanitizes to nothing.
     */
    @Synchronized
    fun fileName(
        rawBase: String,
        fallbackBase: String,
        extension: String,
    ): String {
        val base = sanitizeBase(rawBase, fallbackBase)
        val stamp = formatter.format(clock.instant())
        if (stamp != currentStamp) {
            currentStamp = stamp
            issued.clear()
        }
        var candidate = "$base-$stamp$extension"
        var next = 2
        while (!issued.add(candidate)) {
            candidate = "$base-$stamp-$next$extension"
            next++
        }
        return candidate
    }

    companion object {
        /** Keeps names comfortably inside every relevant limit while staying readable. */
        private const val MAX_BASE_LENGTH = 80

        /** Characters that are path separators or classic filesystem troublemakers. */
        private const val UNSAFE = "/\\:*?\"<>|"

        /**
         * Sanitizes a user-entered name into a Drive-safe base: unsafe/control chars
         * become spaces, whitespace collapses, and a blank result falls back.
         */
        fun sanitizeBase(
            raw: String,
            fallback: String,
        ): String {
            val cleaned =
                buildString(raw.length) {
                    for (ch in raw) append(if (ch.isISOControl() || ch in UNSAFE) ' ' else ch)
                }.replace(Regex("\\s+"), " ").trim().take(MAX_BASE_LENGTH).trim()
            return cleaned.ifBlank { fallback }
        }
    }
}
