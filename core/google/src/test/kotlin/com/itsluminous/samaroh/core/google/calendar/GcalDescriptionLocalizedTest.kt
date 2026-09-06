package com.itsluminous.samaroh.core.google.calendar

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.core.model.BookingSource
import com.itsluminous.samaroh.core.testing.Fixtures
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * ADR-047: the rich event description resolves REAL catalog resources through the exact
 * wiring the engine uses ([descriptionStrings]) — line-by-line content pinned in BOTH
 * locales, so a catalog regression (missing key, broken placeholder) fails here.
 */
@RunWith(RobolectricTestRunner::class)
class GcalDescriptionLocalizedTest {
    private fun localizedContext(lang: String): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale(lang))
        return base.createConfigurationContext(config)
    }

    private val booking =
        Fixtures
            .booking(totalAmountPaise = 1_50_000_00L, securityDepositPaise = 10_000_00L)
            .copy(
                customerPhone = "9876543210",
                invoiceNumber = "INV-7",
                source = BookingSource.PHONE,
                notes = "stage by 4 pm",
            )

    @Test
    fun `english description carries every field on its own line`() {
        val strings = descriptionStrings(localizedContext("en"))
        val lines = GcalEventMapper.description(booking, paidPaise = 50_000_00L, strings = strings).split("\n")
        assertThat(lines)
            .containsExactly(
                "Customer name: fixture-customer",
                "Phone number: 9876543210",
                "Event type: wedding",
                "Status: Confirmed",
                "Total: ₹1,50,000",
                "Security deposit: ₹10,000",
                "Advance paid: ₹50,000",
                "Due: ₹1,00,000",
                "Invoice number: INV-7",
                "Booking source: Phone",
                "Notes: stage by 4 pm",
                "Managed by Samaroh",
            ).inOrder()
    }

    @Test
    fun `hindi description carries every field on its own line`() {
        val strings = descriptionStrings(localizedContext("hi"))
        val lines = GcalEventMapper.description(booking, paidPaise = 50_000_00L, strings = strings).split("\n")
        assertThat(lines)
            .containsExactly(
                "ग्राहक का नाम: fixture-customer",
                "फ़ोन नंबर: 9876543210",
                "आयोजन का प्रकार: wedding",
                "स्थिति: पक्की",
                "कुल राशि: ₹1,50,000",
                "सिक्योरिटी जमा: ₹10,000",
                "एडवांस मिला: ₹50,000",
                "बाकी: ₹1,00,000",
                "चालान नंबर: INV-7",
                "बुकिंग कहाँ से आई: फ़ोन",
                "नोट्स: stage by 4 pm",
                "Samaroh द्वारा प्रबंधित",
            ).inOrder()
    }
}
