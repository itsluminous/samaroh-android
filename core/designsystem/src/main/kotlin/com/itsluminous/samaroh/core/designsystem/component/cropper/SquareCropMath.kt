package com.itsluminous.samaroh.core.designsystem.component.cropper

import kotlin.math.roundToInt

/** A square region within a source bitmap, in source pixels. */
data class CropRegion(
    val left: Int,
    val top: Int,
    val size: Int,
)

/**
 * Zoom + pan state of the crop viewport. [offsetX]/[offsetY] are the image translation in
 * viewport pixels relative to the centered position (positive x moves the image right).
 */
data class CropTransform(
    val zoom: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

/**
 * Pure geometry for the interactive square cropper: the image is rendered under a fixed
 * square viewport of side `viewportPx` at `baseScale * zoom`, shifted by the pan offset.
 * At zoom 1 the image exactly covers the viewport on its shorter side ("fill"), so the
 * crop can never include out-of-image area — offsets are clamped to keep the viewport
 * fully inside the image.
 */
object SquareCropMath {
    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 8f

    /** Scale at zoom 1: the image's shorter side exactly covers the square viewport. */
    fun baseScale(
        imageWidth: Int,
        imageHeight: Int,
        viewportPx: Float,
    ): Float = viewportPx / minOf(imageWidth, imageHeight).toFloat()

    /** Clamps zoom into [[MIN_ZOOM], [MAX_ZOOM]] and pan so the image always covers the viewport. */
    fun clamp(
        imageWidth: Int,
        imageHeight: Int,
        viewportPx: Float,
        transform: CropTransform,
    ): CropTransform {
        val zoom = transform.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val scale = baseScale(imageWidth, imageHeight, viewportPx) * zoom
        val maxX = ((imageWidth * scale - viewportPx) / 2f).coerceAtLeast(0f)
        val maxY = ((imageHeight * scale - viewportPx) / 2f).coerceAtLeast(0f)
        return CropTransform(
            zoom = zoom,
            offsetX = transform.offsetX.coerceIn(-maxX, maxX),
            offsetY = transform.offsetY.coerceIn(-maxY, maxY),
        )
    }

    /**
     * Applies one transform-gesture step: pinch about the gesture centroid (the image
     * point under the fingers stays under them) plus the pan delta, then clamps.
     * [centroidX]/[centroidY] are in viewport coordinates (origin = viewport top-left),
     * as reported by `detectTransformGestures` inside the viewport box.
     */
    fun applyGesture(
        imageWidth: Int,
        imageHeight: Int,
        viewportPx: Float,
        current: CropTransform,
        centroidX: Float,
        centroidY: Float,
        panX: Float,
        panY: Float,
        zoomChange: Float,
    ): CropTransform {
        val newZoom = (current.zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val ratio = newZoom / current.zoom // == scaleNew / scaleOld
        val cx = centroidX - viewportPx / 2f
        val cy = centroidY - viewportPx / 2f
        return clamp(
            imageWidth,
            imageHeight,
            viewportPx,
            CropTransform(
                zoom = newZoom,
                offsetX = cx - (cx - current.offsetX) * ratio + panX,
                offsetY = cy - (cy - current.offsetY) * ratio + panY,
            ),
        )
    }

    /**
     * Maps the viewport back to source pixels: the square of the image visible under the
     * viewport for the (clamped) [transform]. Always within image bounds, side ≥ 1.
     */
    fun cropRegion(
        imageWidth: Int,
        imageHeight: Int,
        viewportPx: Float,
        transform: CropTransform,
    ): CropRegion {
        val clamped = clamp(imageWidth, imageHeight, viewportPx, transform)
        val scale = baseScale(imageWidth, imageHeight, viewportPx) * clamped.zoom
        val sourceSize = viewportPx / scale
        val centerX = imageWidth / 2f - clamped.offsetX / scale
        val centerY = imageHeight / 2f - clamped.offsetY / scale
        val size = sourceSize.roundToInt().coerceIn(1, minOf(imageWidth, imageHeight))
        return CropRegion(
            left = (centerX - sourceSize / 2f).roundToInt().coerceIn(0, imageWidth - size),
            top = (centerY - sourceSize / 2f).roundToInt().coerceIn(0, imageHeight - size),
            size = size,
        )
    }
}
