package com.itsluminous.samaroh.core.designsystem.component.cropper

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-geometry tests for [SquareCropMath] (spec: interactive square crop on upload).
 * Viewport is a 300px square unless stated; images are landscape 1200×600, portrait
 * 600×1200 or square 800×800 to cover both fill orientations.
 */
class SquareCropMathTest {
    private val viewport = 300f

    // ---- baseScale ----

    @Test
    fun `base scale fills the viewport with the shorter side`() {
        // landscape: height (600) is the shorter side → 300/600 = 0.5
        assertThat(SquareCropMath.baseScale(1200, 600, viewport)).isEqualTo(0.5f)
        // portrait: width (600) is the shorter side
        assertThat(SquareCropMath.baseScale(600, 1200, viewport)).isEqualTo(0.5f)
        // an image smaller than the viewport is scaled UP to cover it
        assertThat(SquareCropMath.baseScale(100, 150, viewport)).isEqualTo(3f)
    }

    // ---- clamp ----

    @Test
    fun `zoom is clamped into the allowed range`() {
        val below = SquareCropMath.clamp(1200, 600, viewport, CropTransform(zoom = 0.2f))
        assertThat(below.zoom).isEqualTo(SquareCropMath.MIN_ZOOM)
        val above = SquareCropMath.clamp(1200, 600, viewport, CropTransform(zoom = 99f))
        assertThat(above.zoom).isEqualTo(SquareCropMath.MAX_ZOOM)
    }

    @Test
    fun `square image at zoom 1 allows no pan at all`() {
        val clamped = SquareCropMath.clamp(800, 800, viewport, CropTransform(zoom = 1f, offsetX = 50f, offsetY = -80f))
        assertThat(clamped.offsetX).isWithin(0f).of(0f)
        assertThat(clamped.offsetY).isWithin(0f).of(0f)
    }

    @Test
    fun `landscape image at zoom 1 pans only horizontally, to the slack limit`() {
        // displayed width = 1200*0.5 = 600 → horizontal slack = (600-300)/2 = 150 each way
        val clamped = SquareCropMath.clamp(1200, 600, viewport, CropTransform(zoom = 1f, offsetX = 9999f, offsetY = 9999f))
        assertThat(clamped.offsetX).isEqualTo(150f)
        assertThat(clamped.offsetY).isWithin(0f).of(0f)
        val negative = SquareCropMath.clamp(1200, 600, viewport, CropTransform(zoom = 1f, offsetX = -9999f, offsetY = -1f))
        assertThat(negative.offsetX).isEqualTo(-150f)
        assertThat(negative.offsetY).isWithin(0f).of(0f)
    }

    @Test
    fun `zooming in opens pan slack on both axes`() {
        // zoom 2 → scale = 0.5*2 = 1 → displayed 1200×600 → slackX = (1200-300)/2 = 450, slackY = (600-300)/2 = 150
        val clamped = SquareCropMath.clamp(1200, 600, viewport, CropTransform(zoom = 2f, offsetX = -99999f, offsetY = 99999f))
        assertThat(clamped.offsetX).isEqualTo(-450f)
        assertThat(clamped.offsetY).isEqualTo(150f)
    }

    // ---- cropRegion: viewport → source mapping ----

    @Test
    fun `default transform maps to the centered max square`() {
        val region = SquareCropMath.cropRegion(1200, 600, viewport, CropTransform())
        assertThat(region).isEqualTo(CropRegion(left = 300, top = 0, size = 600))

        val portrait = SquareCropMath.cropRegion(600, 1200, viewport, CropTransform())
        assertThat(portrait).isEqualTo(CropRegion(left = 0, top = 300, size = 600))
    }

    @Test
    fun `pan at zoom 1 slides the crop window along the long axis`() {
        // offsetX +150 (max) moves the image right → viewport shows the LEFT edge
        val leftEdge = SquareCropMath.cropRegion(1200, 600, viewport, CropTransform(offsetX = 150f))
        assertThat(leftEdge).isEqualTo(CropRegion(left = 0, top = 0, size = 600))
        val rightEdge = SquareCropMath.cropRegion(1200, 600, viewport, CropTransform(offsetX = -150f))
        assertThat(rightEdge).isEqualTo(CropRegion(left = 600, top = 0, size = 600))
    }

    @Test
    fun `zoom shrinks the source square proportionally`() {
        // zoom 2 → source side = 600/2 = 300, still centered
        val region = SquareCropMath.cropRegion(1200, 600, viewport, CropTransform(zoom = 2f))
        assertThat(region).isEqualTo(CropRegion(left = 450, top = 150, size = 300))
    }

    @Test
    fun `max zoom with extreme pan maps to the exact source corner`() {
        val zoom = SquareCropMath.MAX_ZOOM // source side = 600/8 = 75
        val corner =
            SquareCropMath.cropRegion(
                1200,
                600,
                viewport,
                CropTransform(zoom = zoom, offsetX = Float.MAX_VALUE, offsetY = Float.MAX_VALUE),
            )
        assertThat(corner).isEqualTo(CropRegion(left = 0, top = 0, size = 75))

        val opposite =
            SquareCropMath.cropRegion(
                1200,
                600,
                viewport,
                CropTransform(zoom = zoom, offsetX = -Float.MAX_VALUE, offsetY = -Float.MAX_VALUE),
            )
        assertThat(opposite).isEqualTo(CropRegion(left = 1200 - 75, top = 600 - 75, size = 75))
    }

    @Test
    fun `region always stays within image bounds even with odd sizes`() {
        // odd dimensions + fractional scale exercise the rounding paths
        for (zoom in listOf(1f, 1.37f, 2.9f, SquareCropMath.MAX_ZOOM)) {
            for (ox in listOf(-5000f, -3.3f, 0f, 7.7f, 5000f)) {
                for (oy in listOf(-5000f, 0f, 5000f)) {
                    val region = SquareCropMath.cropRegion(1013, 777, viewport, CropTransform(zoom, ox, oy))
                    assertThat(region.size).isAtLeast(1)
                    assertThat(region.left).isAtLeast(0)
                    assertThat(region.top).isAtLeast(0)
                    assertThat(region.left + region.size).isAtMost(1013)
                    assertThat(region.top + region.size).isAtMost(777)
                }
            }
        }
    }

    @Test
    fun `tiny image never produces a zero-size region`() {
        val region = SquareCropMath.cropRegion(1, 1, viewport, CropTransform(zoom = SquareCropMath.MAX_ZOOM))
        assertThat(region).isEqualTo(CropRegion(0, 0, 1))
    }

    // ---- applyGesture ----

    @Test
    fun `pan gesture translates the offset`() {
        val next =
            SquareCropMath.applyGesture(
                1200,
                600,
                viewport,
                CropTransform(zoom = 2f),
                centroidX = 150f,
                centroidY = 150f,
                panX = 40f,
                panY = -25f,
                zoomChange = 1f,
            )
        assertThat(next).isEqualTo(CropTransform(zoom = 2f, offsetX = 40f, offsetY = -25f))
    }

    @Test
    fun `pinch about the viewport center keeps the crop centered`() {
        val next =
            SquareCropMath.applyGesture(
                1200,
                600,
                viewport,
                CropTransform(),
                centroidX = viewport / 2f,
                centroidY = viewport / 2f,
                panX = 0f,
                panY = 0f,
                zoomChange = 2f,
            )
        assertThat(next).isEqualTo(CropTransform(zoom = 2f, offsetX = 0f, offsetY = 0f))
    }

    @Test
    fun `pinch keeps the image point under the centroid fixed`() {
        val start = CropTransform(zoom = 2f, offsetX = -100f, offsetY = 30f)
        val centroidX = 250f
        val centroidY = 60f
        val next =
            SquareCropMath.applyGesture(
                1200,
                600,
                viewport,
                start,
                centroidX = centroidX,
                centroidY = centroidY,
                panX = 0f,
                panY = 0f,
                zoomChange = 1.5f,
            )

        // Source point under the centroid before and after must match (unclamped region here).
        fun sourcePointUnder(
            t: CropTransform,
            viewX: Float,
            viewY: Float,
        ): Pair<Float, Float> {
            val scale = SquareCropMath.baseScale(1200, 600, viewport) * t.zoom
            val cx = viewX - viewport / 2f
            val cy = viewY - viewport / 2f
            return Pair(600f + (cx - t.offsetX) / scale, 300f + (cy - t.offsetY) / scale)
        }
        val (beforeX, beforeY) = sourcePointUnder(start, centroidX, centroidY)
        val (afterX, afterY) = sourcePointUnder(next, centroidX, centroidY)
        assertThat(afterX).isWithin(0.001f).of(beforeX)
        assertThat(afterY).isWithin(0.001f).of(beforeY)
        assertThat(next.zoom).isEqualTo(3f)
    }

    @Test
    fun `pinch out beyond min zoom snaps back to a legal transform`() {
        val next =
            SquareCropMath.applyGesture(
                1200,
                600,
                viewport,
                CropTransform(zoom = 1.2f, offsetX = 100f, offsetY = 20f),
                centroidX = 10f,
                centroidY = 290f,
                panX = 0f,
                panY = 0f,
                zoomChange = 0.1f,
            )
        assertThat(next.zoom).isEqualTo(SquareCropMath.MIN_ZOOM)
        // at zoom 1 on a 1200×600 image the vertical offset must collapse to 0
        assertThat(next.offsetY).isEqualTo(0f)
        assertThat(next.offsetX).isAtLeast(-150f)
        assertThat(next.offsetX).isAtMost(150f)
    }
}
