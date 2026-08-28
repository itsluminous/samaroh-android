package com.itsluminous.samaroh.applink

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** URI-path → [AppLink] mapping for `https://samaroh-web.vercel.app` links (ADR-033). */
class AppLinkParserTest {
    // --- Booking -------------------------------------------------------------------

    @Test
    fun `booking path routes to booking tab`() {
        assertThat(AppLink.parse("/en/booking")).isEqualTo(AppLink.Booking)
    }

    @Test
    fun `hindi locale is stripped like english`() {
        assertThat(AppLink.parse("/hi/booking")).isEqualTo(AppLink.Booking)
    }

    @Test
    fun `booking with trailing slash routes to booking tab`() {
        assertThat(AppLink.parse("/en/booking/")).isEqualTo(AppLink.Booking)
    }

    @Test
    fun `bare locale routes to booking fallback`() {
        assertThat(AppLink.parse("/en")).isEqualTo(AppLink.Booking)
    }

    @Test
    fun `root path routes to booking fallback`() {
        assertThat(AppLink.parse("/")).isEqualTo(AppLink.Booking)
    }

    @Test
    fun `null path routes to booking fallback`() {
        assertThat(AppLink.parse(null)).isEqualTo(AppLink.Booking)
    }

    @Test
    fun `empty path routes to booking fallback`() {
        assertThat(AppLink.parse("")).isEqualTo(AppLink.Booking)
    }

    @Test
    fun `unknown section routes to booking fallback`() {
        assertThat(AppLink.parse("/en/whatever")).isEqualTo(AppLink.Booking)
    }

    @Test
    fun `unknown locale segment is treated as unknown section`() {
        assertThat(AppLink.parse("/fr/booking")).isEqualTo(AppLink.Booking)
    }

    @Test
    fun `path without locale still maps its section`() {
        assertThat(AppLink.parse("/expenses")).isEqualTo(AppLink.Expenses())
    }

    @Test
    fun `matching is case-insensitive`() {
        assertThat(AppLink.parse("/EN/Booking")).isEqualTo(AppLink.Booking)
    }

    // --- Expenses ------------------------------------------------------------------

    @Test
    fun `expenses path routes to expenses tab`() {
        assertThat(AppLink.parse("/en/expenses")).isEqualTo(AppLink.Expenses(partyId = null))
    }

    @Test
    fun `expenses with party id targets that ledger`() {
        assertThat(AppLink.parse("/hi/expenses/party-123"))
            .isEqualTo(AppLink.Expenses(partyId = "party-123"))
    }

    @Test
    fun `party id casing is preserved verbatim`() {
        assertThat(AppLink.parse("/en/expenses/Party-ABC"))
            .isEqualTo(AppLink.Expenses(partyId = "Party-ABC"))
    }

    @Test
    fun `extra segments after party id are ignored`() {
        assertThat(AppLink.parse("/en/expenses/party-123/extra"))
            .isEqualTo(AppLink.Expenses(partyId = "party-123"))
    }

    @Test
    fun `expenses with trailing slash has no party id`() {
        assertThat(AppLink.parse("/en/expenses/")).isEqualTo(AppLink.Expenses(partyId = null))
    }

    // --- Inventory -----------------------------------------------------------------

    @Test
    fun `inventory path routes to inventory tab`() {
        assertThat(AppLink.parse("/en/inventory")).isEqualTo(AppLink.Inventory(masterlist = false))
    }

    @Test
    fun `inventory masterlist routes to masterlist`() {
        assertThat(AppLink.parse("/en/inventory/masterlist"))
            .isEqualTo(AppLink.Inventory(masterlist = true))
    }

    @Test
    fun `unknown inventory subpath stays on inventory tab`() {
        assertThat(AppLink.parse("/en/inventory/unknown"))
            .isEqualTo(AppLink.Inventory(masterlist = false))
    }

    // --- Menu ----------------------------------------------------------------------

    @Test
    fun `menu path routes to menu tab`() {
        assertThat(AppLink.parse("/en/menu")).isEqualTo(AppLink.Menu(settings = false))
    }

    @Test
    fun `menu settings routes to settings`() {
        assertThat(AppLink.parse("/en/menu/settings")).isEqualTo(AppLink.Menu(settings = true))
    }

    @Test
    fun `menu settings subroute routes to settings`() {
        assertThat(AppLink.parse("/en/menu/settings/language"))
            .isEqualTo(AppLink.Menu(settings = true))
    }

    @Test
    fun `menu reports routes to reports`() {
        assertThat(AppLink.parse("/en/menu/reports")).isEqualTo(AppLink.Reports)
    }

    @Test
    fun `unknown menu subroute stays on menu tab`() {
        assertThat(AppLink.parse("/en/menu/other")).isEqualTo(AppLink.Menu(settings = false))
    }

    // --- Malformed -----------------------------------------------------------------

    @Test
    fun `repeated slashes are tolerated`() {
        assertThat(AppLink.parse("//en///expenses//")).isEqualTo(AppLink.Expenses(partyId = null))
    }

    @Test
    fun `whitespace-only segments are ignored`() {
        assertThat(AppLink.parse("/en/ /booking")).isEqualTo(AppLink.Booking)
    }
}
