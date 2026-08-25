package com.itsluminous.samaroh.core.invoice

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.OutputStream

/**
 * Robolectric 4.15 ships no natives for `android.graphics.pdf`, so [PdfDocument] cannot
 * run as-is in unit tests. This test-only shadow keeps the smoke test meaningful:
 * every page hands the renderer a REAL bitmap-backed [Canvas] (native graphics), so the
 * full painter — text, paths, tables, the tonal band — executes against real Skia; only
 * the final serialization is replaced with a minimal `%PDF` byte stream whose size scales
 * with the finished page count. Pixel-true PDF output is covered by the on-device
 * instrumented suite (W2-B).
 */
@Implements(PdfDocument::class)
class ShadowPdfDocument {
    private var finishedPages = 0
    private var closed = false

    @Implementation
    @Suppress("unused", "ktlint:standard:function-naming")
    fun __constructor__() {
        // Skip the real constructor's nativeCreateDocument() call.
    }

    @Implementation
    @Suppress("unused")
    fun startPage(pageInfo: PdfDocument.PageInfo): PdfDocument.Page {
        check(!closed) { "document is closed" }
        val bitmap = Bitmap.createBitmap(pageInfo.pageWidth, pageInfo.pageHeight, Bitmap.Config.ARGB_8888)
        return ReflectionHelpers.callConstructor(
            PdfDocument.Page::class.java,
            ClassParameter(Canvas::class.java, Canvas(bitmap)),
            ClassParameter(PdfDocument.PageInfo::class.java, pageInfo),
        )
    }

    @Implementation
    @Suppress("unused", "UNUSED_PARAMETER")
    fun finishPage(page: PdfDocument.Page) {
        finishedPages++
    }

    @Implementation
    @Suppress("unused")
    fun writeTo(out: OutputStream) {
        check(!closed) { "document is closed" }
        out.write("%PDF-1.4\n".toByteArray(Charsets.US_ASCII))
        repeat(finishedPages) { index ->
            out.write("% page ${index + 1} placeholder object\n".toByteArray(Charsets.US_ASCII))
        }
        out.write("%%EOF\n".toByteArray(Charsets.US_ASCII))
    }

    @Implementation
    @Suppress("unused")
    fun close() {
        closed = true
    }
}
