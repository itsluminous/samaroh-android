package com.itsluminous.samaroh.core.designsystem.imaging

import android.graphics.Bitmap
import android.os.Build
import java.io.File

/**
 * Per-use-case image encoding level: quality, longest-side cap and container format.
 * One shared pipeline ([ImageCompression]) — each call site passes its own spec, so
 * "how hard do we squeeze" is an argument, never a copy-pasted constant (ADR-050).
 *
 * The owner expresses levels as "~N% compression"; that maps to encoder
 * `quality = 100 − N` (JPEG/WebP quality scale). Dimension caps are per use-case and
 * unchanged by the level.
 */
data class CompressionSpec(
    /** Encoder quality 0..100 (higher = larger file, better fidelity). */
    val quality: Int,
    /** Longest allowed output side in pixels; larger inputs are scaled down, never up. */
    val maxDimensionPx: Int,
    val format: OutputFormat,
) {
    enum class OutputFormat { JPEG, WEBP }

    companion object {
        /**
         * Invoice/bill photo attachments: LIGHT (~10%) compression — documents must stay
         * readable, so quality 90 at up to 2048px (§4.2, ADR-050).
         */
        val DocumentLight = CompressionSpec(quality = 90, maxDimensionPx = 2048, format = OutputFormat.JPEG)

        /**
         * Inventory item photos: HEAVY (~50%) compression — thumbnails rendered small,
         * so quality 50 at the existing ≤320px WebP dimensions (§1.1/§4.3, ADR-050).
         */
        val ItemPhoto = CompressionSpec(quality = 50, maxDimensionPx = 320, format = OutputFormat.WEBP)

        /** Business logo (onboarding + settings): unchanged treatment, WebP 85 ≤320px (§4.0/§4.4). */
        val Logo = CompressionSpec(quality = 85, maxDimensionPx = 320, format = OutputFormat.WEBP)
    }
}

/**
 * The one shared scale-and-encode pipeline behind every stored image (attachments,
 * item photos, logos). Callers decide WHICH pixels go in (interactive crop, center
 * square, or the full frame) and at WHAT level ([CompressionSpec]); this object owns
 * the memory-safe scaling and the format/API-level details.
 */
object ImageCompression {
    /**
     * Power-of-two subsampling factor that brings [longestEdgePx] near (never below)
     * [maxDimensionPx] — coarse first pass so a huge photo is never decoded whole.
     */
    fun sampleSizeFor(
        longestEdgePx: Int,
        maxDimensionPx: Int,
    ): Int {
        var sample = 1
        while (longestEdgePx / (sample * 2) >= maxDimensionPx) sample *= 2
        return sample
    }

    /**
     * Exact scale down to [maxDimensionPx] on the longest side, aspect preserved.
     * Returns [bitmap] itself when already within bounds — images are never upscaled.
     */
    fun scaleToMaxDimension(
        bitmap: Bitmap,
        maxDimensionPx: Int,
    ): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDimensionPx) return bitmap
        val scale = maxDimensionPx.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    /**
     * Scales [bitmap] per [spec] and encodes it into [target]. The input bitmap is
     * caller-owned and never recycled here; an internal scaled copy is.
     * Returns the written byte count.
     */
    fun encodeToFile(
        bitmap: Bitmap,
        spec: CompressionSpec,
        target: File,
    ): Long {
        val scaled = scaleToMaxDimension(bitmap, spec.maxDimensionPx)
        try {
            target.outputStream().use { out -> scaled.compress(compressFormat(spec.format), spec.quality, out) }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
        return target.length()
    }

    private fun compressFormat(format: CompressionSpec.OutputFormat): Bitmap.CompressFormat =
        when (format) {
            CompressionSpec.OutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
            CompressionSpec.OutputFormat.WEBP ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
        }
}
