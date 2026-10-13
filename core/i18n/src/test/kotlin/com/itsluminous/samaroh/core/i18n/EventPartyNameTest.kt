package com.itsluminous.samaroh.core.i18n

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * The event party name is STORED, device-independent data (ADR-106, shared contract
 * §2.1): English month, ASCII digits, ` - ` separator — whatever the device locale.
 */
class EventPartyNameTest {
    private lateinit var defaultLocale: Locale

    @Before
    fun rememberLocale() {
        defaultLocale = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun `builds Type - dd-MMM-yyyy - Customer with the contract's example`() {
        assertThat(EventPartyName.build("Wedding", LocalDate.of(2026, 10, 20), "Sandhya Upadhyay"))
            .isEqualTo("Wedding - 20-Oct-2026 - Sandhya Upadhyay")
    }

    @Test
    fun `date is two-digit day, English three-letter month with initial capital, four-digit year`() {
        assertThat(EventPartyName.formatDate(LocalDate.of(2026, 1, 5))).isEqualTo("05-Jan-2026")
        assertThat(EventPartyName.formatDate(LocalDate.of(2026, 9, 30))).isEqualTo("30-Sep-2026") // never "Sept"
        assertThat(EventPartyName.formatDate(LocalDate.of(2027, 12, 1))).isEqualTo("01-Dec-2027")
        val months = (1..12).map { EventPartyName.formatDate(LocalDate.of(2026, it, 15)).substring(3, 6) }
        assertThat(months).containsExactly("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec").inOrder()
    }

    @Test
    fun `name is identical under a Hindi default locale (no Devanagari digits or months)`() {
        val english = EventPartyName.build("Wedding", LocalDate.of(2026, 10, 20), "Sandhya Upadhyay")
        Locale.setDefault(Locale.forLanguageTag("hi-IN-u-nu-deva"))
        assertThat(EventPartyName.build("Wedding", LocalDate.of(2026, 10, 20), "Sandhya Upadhyay")).isEqualTo(english)
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        assertThat(EventPartyName.build("Wedding", LocalDate.of(2026, 10, 20), "Sandhya Upadhyay")).isEqualTo(english)
        assertThat(english).matches("[A-Za-z]+ - \\d{2}-[A-Z][a-z]{2}-\\d{4} - .*")
    }

    @Test
    fun `customer name is trimmed and internal whitespace runs collapse to one space`() {
        assertThat(EventPartyName.build("Wedding", LocalDate.of(2026, 10, 20), "  Sandhya   Upadhyay\t "))
            .isEqualTo("Wedding - 20-Oct-2026 - Sandhya Upadhyay")
    }

    @Test
    fun `event type label is the stored preset label trimmed, a Hindi custom label stays Hindi`() {
        val english = { _: Int -> error("not a built-in key") }
        assertThat(EventPartyName.eventTypeLabel(" Haldi ", english)).isEqualTo("Haldi")
        assertThat(EventPartyName.eventTypeLabel("शादी", english)).isEqualTo("शादी")
        assertThat(EventPartyName.eventTypeLabel("Haldi Ceremony", english)).isEqualTo("Haldi Ceremony")
    }

    @Test
    fun `legacy built-in KEYS resolve to their English catalog label by the normalize rule`() {
        val english = { res: Int ->
            when (res) {
                R.string.booking_event_type_wedding -> "Wedding"
                R.string.booking_event_type_room_booking -> "Room Booking"
                R.string.booking_event_type_muh_dikhayi -> "Muh Dikhayi"
                else -> error("unexpected $res")
            }
        }
        assertThat(EventPartyName.eventTypeLabel("wedding", english)).isEqualTo("Wedding")
        assertThat(EventPartyName.eventTypeLabel("room_booking", english)).isEqualTo("Room Booking")
        // The stored label of the seeded preset normalizes to the same key → same English label.
        assertThat(EventPartyName.eventTypeLabel("Room Booking", english)).isEqualTo("Room Booking")
        assertThat(EventPartyName.eventTypeLabel("muh_dikhayi", english)).isEqualTo("Muh Dikhayi")
    }
}
