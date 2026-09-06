package com.itsluminous.samaroh.feature.onboarding.logo

import android.content.Context
import android.graphics.Bitmap
import com.itsluminous.samaroh.core.designsystem.imaging.CompressionSpec
import com.itsluminous.samaroh.core.designsystem.imaging.ImageCompression
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Centered square crop region within a source image. */
data class CropRect(
    val left: Int,
    val top: Int,
    val size: Int,
)

/** Pure geometry for the logo pipeline (camera/gallery → square crop → ≤320px, §4.0). */
object ImageCropMath {
    const val MAX_LOGO_SIZE_PX = 320

    /** Largest centered square inside a width×height image. */
    fun squareCropRect(
        width: Int,
        height: Int,
    ): CropRect {
        val size = minOf(width, height)
        return CropRect(left = (width - size) / 2, top = (height - size) / 2, size = size)
    }

    /** Final edge length: never upscale, never exceed [maxSize]. */
    fun outputSize(
        cropSize: Int,
        maxSize: Int = MAX_LOGO_SIZE_PX,
    ): Int = minOf(cropSize, maxSize)
}

/**
 * Processes a picked/captured logo into a square, ≤320px WebP file (same approach as
 * inventory images, §1.1) under the app's private files dir. No storage permission needed.
 * Encoding rides the shared pipeline at the LOGO level (unchanged treatment, ADR-050).
 */
@Singleton
class LogoProcessor
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        suspend fun process(source: Bitmap): String =
            withContext(Dispatchers.IO) {
                val crop = ImageCropMath.squareCropRect(source.width, source.height)
                val squared = Bitmap.createBitmap(source, crop.left, crop.top, crop.size, crop.size)
                val dir = File(context.filesDir, "logos").apply { mkdirs() }
                val file = File(dir, "business-logo-${System.currentTimeMillis()}.webp")
                ImageCompression.encodeToFile(squared, CompressionSpec.Logo, file)
                if (squared !== source) squared.recycle()
                file.absolutePath
            }
    }
