package com.itsluminous.samaroh.core.invoice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [ShadowPdfDocument::class])
class AndroidInvoiceGeneratorTest {
    private lateinit var harness: InvoiceTestHarness
    private lateinit var generator: AndroidInvoiceGenerator

    @Before
    fun setUp() {
        harness = InvoiceTestHarness(invoiceTestDatabase())
        val context = ApplicationProvider.getApplicationContext<Context>()
        generator =
            AndroidInvoiceGenerator(
                loader = harness.loader,
                renderer = PdfInvoiceRenderer(context),
                textBuilder = InvoiceTextBuilder(context),
            )
    }

    @After
    fun tearDown() {
        harness.db.close()
    }

    @Test
    fun `pdf smoke - rendering produces a non-empty pdf with at least one page`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val rendered = PdfInvoiceRenderer(context).render(invoiceData())

        assertThat(rendered.pageCount).isAtLeast(1)
        assertThat(rendered.sizeBytes).isGreaterThan(0L)
        val file = File(rendered.filePath)
        assertThat(file.exists()).isTrue()
        assertThat(file.readBytes().toString(Charsets.US_ASCII)).startsWith("%PDF")
    }

    @Test
    fun `hindi render also produces a non-empty pdf`() {
        val rendered = PdfInvoiceRenderer(localizedContext("hi")).render(invoiceData(invoiceNumber = "INV-2026-0007"))

        assertThat(rendered.pageCount).isAtLeast(1)
        assertThat(rendered.sizeBytes).isGreaterThan(0L)
    }

    @Test
    fun `many payments overflow onto additional pages`() {
        val data = invoiceData(paymentAmountsPaise = List(60) { 1_000_00L })

        val rendered = PdfInvoiceRenderer(ApplicationProvider.getApplicationContext<Context>()).render(data)

        assertThat(rendered.pageCount).isAtLeast(2)
    }

    @Test
    fun `end to end - pdf is saved under the frozen contract and the number is assigned once`() =
        runTest {
            harness.seedBusinessAndBooking(bookingId = "booking-1", advancePaise = 50_000_00L)

            val result = generator.generateInvoicePdf("booking-1")

            assertThat(result.isSuccess).isTrue()
            val path = result.getOrThrow()
            assertThat(path).contains("INV-2026-0001")
            assertThat(File(path).length()).isGreaterThan(0L)

            // Text receipt agrees on the frozen number; no second counter value is consumed.
            val text = generator.buildInvoiceText("booking-1")
            assertThat(text).contains("INV-2026-0001")
            assertThat(harness.businessRepository.business(com.itsluminous.samaroh.core.testing.Fixtures.BUSINESS_ID)!!.invoiceCounter)
                .isEqualTo(1)
        }

    @Test
    fun `missing booking yields a failed result, never a throw`() =
        runTest {
            val result = generator.generateInvoicePdf("missing-booking")

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        }
}
