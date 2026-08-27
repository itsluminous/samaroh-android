package com.itsluminous.samaroh.core.designsystem.component.cropper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.exifinterface.media.ExifInterface
import com.itsluminous.samaroh.core.i18n.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

/** Longest side the crop-source bitmap is downsampled to before interactive cropping. */
private const val CROP_SOURCE_MAX_DIMENSION_PX = 1600

/**
 * Full-screen dialog for choosing a square crop of [bitmap] interactively: the image is
 * pinch-zoomed and panned under a fixed square viewport (spec: parity with the web app's
 * upload cropper). Confirming maps the viewport back to source pixels via [SquareCropMath]
 * and returns the cropped square bitmap; downstream code compresses it (WebP ≤320px).
 */
@Composable
fun SquareImageCropperDialog(
    bitmap: Bitmap,
    onConfirm: (Bitmap) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var transform by remember(bitmap) { mutableStateOf(CropTransform()) }
        var viewportPx by remember(bitmap) { mutableStateOf(0f) }

        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.common_cropper_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    modifier = Modifier.padding(vertical = 12.dp),
                )

                BoxWithConstraints(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    val density = LocalDensity.current
                    val sidePx = min(constraints.maxWidth, constraints.maxHeight).toFloat()
                    viewportPx = sidePx
                    val sideDp = with(density) { sidePx.toDp() }
                    val imageDescription = stringResource(R.string.common_cropper_image_desc)

                    Box(
                        modifier =
                            Modifier
                                .size(sideDp)
                                .clipToBounds()
                                .semantics { contentDescription = imageDescription }
                                .pointerInput(bitmap, sidePx) {
                                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                                        transform =
                                            SquareCropMath.applyGesture(
                                                imageWidth = bitmap.width,
                                                imageHeight = bitmap.height,
                                                viewportPx = sidePx,
                                                current = transform,
                                                centroidX = centroid.x,
                                                centroidY = centroid.y,
                                                panX = pan.x,
                                                panY = pan.y,
                                                zoomChange = gestureZoom,
                                            )
                                    }
                                },
                    ) {
                        val image = remember(bitmap) { bitmap.asImageBitmap() }
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val clamped = SquareCropMath.clamp(bitmap.width, bitmap.height, sidePx, transform)
                            val scaleFactor = SquareCropMath.baseScale(bitmap.width, bitmap.height, sidePx) * clamped.zoom
                            translate(
                                left = sidePx / 2f + clamped.offsetX - bitmap.width * scaleFactor / 2f,
                                top = sidePx / 2f + clamped.offsetY - bitmap.height * scaleFactor / 2f,
                            ) {
                                scale(scaleFactor, scaleFactor, pivot = Offset.Zero) {
                                    drawImage(image)
                                }
                            }
                            drawCropGrid(sidePx)
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.common_cropper_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 12.dp),
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_action_cancel), color = Color.White)
                    }
                    Button(onClick = {
                        val region =
                            SquareCropMath.cropRegion(bitmap.width, bitmap.height, viewportPx, transform)
                        onConfirm(Bitmap.createBitmap(bitmap, region.left, region.top, region.size, region.size))
                    }) {
                        Text(stringResource(R.string.common_cropper_confirm))
                    }
                }
            }
        }
    }
}

/** Viewport border + rule-of-thirds guides drawn over the image. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCropGrid(sidePx: Float) {
    val border = Color.White
    val guide = Color.White.copy(alpha = 0.35f)
    drawRect(
        color = border,
        size = Size(sidePx, sidePx),
        style =
            androidx.compose.ui.graphics.drawscope
                .Stroke(width = 2.dp.toPx()),
    )
    val third = sidePx / 3f
    for (i in 1..2) {
        drawLine(guide, Offset(third * i, 0f), Offset(third * i, sidePx), strokeWidth = 1.dp.toPx())
        drawLine(guide, Offset(0f, third * i), Offset(sidePx, third * i), strokeWidth = 1.dp.toPx())
    }
}

/**
 * Decodes [uri] into a bitmap suitable as the cropper's source: downsampled near
 * [CROP_SOURCE_MAX_DIMENSION_PX] on the longest side (bounded memory for any gallery
 * photo) and rotated upright per its EXIF orientation — the interactive crop must show
 * the image the way the user shot it. Null when the content is unreadable.
 */
suspend fun loadCropSourceBitmap(
    context: Context,
    uri: Uri,
): Bitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // decodeStream returns null BY DESIGN with inJustDecodeBounds — only the
            // stream-open result decides success here; bounds carry the outcome.
            val boundsStream = resolver.openInputStream(uri) ?: return@runCatching null
            boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= CROP_SOURCE_MAX_DIMENSION_PX) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decoded =
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    ?: return@runCatching null
            // EXIF parsing is best-effort: formats without EXIF support (some PNGs/BMPs)
            // must still crop — they just skip rotation.
            val rotation =
                runCatching {
                    resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
                }.getOrDefault(0)
            if (rotation == 0) {
                decoded
            } else {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            }
        }.getOrNull()
    }
