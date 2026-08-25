package com.itsluminous.samaroh.feature.onboarding.logo

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Logo pipeline geometry (§4.0 step 5: square crop, ≤320px, never upscaled). */
class ImageCropMathTest {
    @Test
    fun `landscape image crops to a centered square of its height`() {
        val crop = ImageCropMath.squareCropRect(width = 1000, height = 400)
        assertThat(crop.size).isEqualTo(400)
        assertThat(crop.left).isEqualTo(300)
        assertThat(crop.top).isEqualTo(0)
    }

    @Test
    fun `portrait image crops to a centered square of its width`() {
        val crop = ImageCropMath.squareCropRect(width = 480, height = 800)
        assertThat(crop.size).isEqualTo(480)
        assertThat(crop.left).isEqualTo(0)
        assertThat(crop.top).isEqualTo(160)
    }

    @Test
    fun `square image is untouched`() {
        val crop = ImageCropMath.squareCropRect(width = 512, height = 512)
        assertThat(crop).isEqualTo(CropRect(left = 0, top = 0, size = 512))
    }

    @Test
    fun `output is capped at 320px and small images are never upscaled`() {
        assertThat(ImageCropMath.outputSize(2048)).isEqualTo(320)
        assertThat(ImageCropMath.outputSize(320)).isEqualTo(320)
        assertThat(ImageCropMath.outputSize(200)).isEqualTo(200)
    }
}
