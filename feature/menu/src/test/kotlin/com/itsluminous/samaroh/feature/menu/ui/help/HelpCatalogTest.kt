package com.itsluminous.samaroh.feature.menu.ui.help

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.samaroh.core.data.navigation.HelpDeepLinkIds
import com.itsluminous.samaroh.core.data.navigation.HelpScreenIds
import com.itsluminous.samaroh.core.help.GrantedPermissions
import com.itsluminous.samaroh.core.help.HelpGuide
import com.itsluminous.samaroh.core.model.BookingPermissions
import com.itsluminous.samaroh.core.model.MemberPermissions
import com.itsluminous.samaroh.feature.menu.helpScreenIdFor
import com.itsluminous.samaroh.feature.menu.routeForHelpTarget
import org.junit.Test
import java.io.File

/**
 * Help screen logic (ADR-108): permission filtering (hide-not-grey), language-agnostic
 * search, list indexing for the `?` pre-scroll, and the Menu host's own route mappings.
 */
class HelpCatalogTest {
    private val guide: HelpGuide =
        HelpGuide.parse(File(checkNotNull(System.getProperty("samaroh.sharedHelpDir")), "guide.json").readText())

    @Test
    fun `owner sees every android screen and task`() {
        val screens = HelpCatalog.visible(guide, GrantedPermissions.OWNER)
        assertThat(screens.map { it.id }).containsExactlyElementsIn(HelpScreenIds.all).inOrder()
        val booking = screens.first { it.id == HelpScreenIds.BOOKING_CARD }
        assertThat(booking.tasks.map { it.id }).contains("record_payment")
        // Web-only steps are gone; android-only ones stay.
        val signIn = screens.first { it.id == HelpScreenIds.SIGN_IN }
        assertThat(signIn.tasks.map { it.id }).contains("join_business")
    }

    @Test
    fun `tasks the member lacks the permission for are hidden, screens too`() {
        val viewer = GrantedPermissions.of(false, MemberPermissions.viewer())
        val screens = HelpCatalog.visible(guide, viewer)
        val card = screens.first { it.id == HelpScreenIds.BOOKING_CARD }
        assertThat(card.tasks.map { it.id }).containsNoneOf("record_payment", "delete_payment", "share_invoice", "cancel_booking")
        assertThat(card.tasks.map { it.id }).containsAtLeast("call_customer", "whatsapp_reminder")
        // record_payment screen requires booking.record_payment → gone entirely.
        assertThat(screens.map { it.id }).doesNotContain(HelpScreenIds.RECORD_PAYMENT)
        // members requires settings.manage_members → gone.
        assertThat(screens.map { it.id }).doesNotContain(HelpScreenIds.MEMBERS)
        // Ungated screens stay.
        assertThat(screens.map { it.id }).containsAtLeast(HelpScreenIds.APP_SHELL, HelpScreenIds.MENU, HelpScreenIds.SETTINGS)
    }

    @Test
    fun `a member with no booking view loses the booking screens`() {
        val none = GrantedPermissions.of(false, MemberPermissions(booking = BookingPermissions(view = false)))
        val ids = HelpCatalog.visible(guide, none).map { it.id }
        assertThat(ids).containsNoneOf(HelpScreenIds.BOOKING_CALENDAR, HelpScreenIds.BOOKING_CARD, HelpScreenIds.BOOKING_FORM)
    }

    @Test
    fun `search matches in either language and keeps only matching tasks`() {
        val all = HelpCatalog.visible(guide, GrantedPermissions.OWNER)
        // An English task title finds the wrongly-entered-payment task on the booking card
        // (the card's summary does not match, so only the matching task is kept)...
        val en = HelpCatalog.search(all, "wrongly entered")
        assertThat(en.map { it.id }).contains(HelpScreenIds.BOOKING_CARD)
        val cardEn = en.first { it.id == HelpScreenIds.BOOKING_CARD }
        assertThat(cardEn.tasks.map { it.id }).containsExactly("delete_payment")
        // ...and so does its Hindi title, regardless of app language.
        val hi = HelpCatalog.search(all, "ग़लती से दर्ज")
        assertThat(hi.first { it.id == HelpScreenIds.BOOKING_CARD }.tasks.map { it.id }).containsExactly("delete_payment")
        // A summary match keeps every task of the screen.
        val summaryMatch = HelpCatalog.search(all, "record a payment")
        assertThat(summaryMatch.first { it.id == HelpScreenIds.BOOKING_CARD }.tasks.size).isEqualTo(8)
        // A screen-level match keeps every task of the screen.
        val screenMatch = HelpCatalog.search(all, "getting around")
        assertThat(screenMatch.map { it.id }).containsExactly(HelpScreenIds.APP_SHELL)
        assertThat(screenMatch.single().tasks.size).isEqualTo(all.first { it.id == HelpScreenIds.APP_SHELL }.tasks.size)
        // Every token must match; nonsense yields nothing; blank yields everything.
        assertThat(HelpCatalog.search(all, "record payment zzzzqqq")).isEmpty()
        assertThat(HelpCatalog.search(all, "   ")).isEqualTo(all)
    }

    @Test
    fun `list index of a screen header accounts for header, tasks and divider rows`() {
        val screens = HelpCatalog.visible(guide, GrantedPermissions.OWNER)
        assertThat(helpListIndexOfScreen(screens, screens[0].id)).isEqualTo(0)
        assertThat(helpListIndexOfScreen(screens, screens[1].id)).isEqualTo(1 + screens[0].tasks.size + 1)
        val keys = helpListKeys(screens)
        for (screen in screens) {
            assertThat(keys[helpListIndexOfScreen(screens, screen.id)!!]).isEqualTo("screen:${screen.id}")
        }
        assertThat(helpListIndexOfScreen(screens, "nope")).isNull()
        assertThat(keys).containsNoDuplicates()
    }

    @Test
    fun `every menu deepLink id maps to a nested route and nothing else does`() {
        for (id in HelpDeepLinkIds.menu) {
            assertWithMessage(id).that(routeForHelpTarget(id)).isNotNull()
        }
        assertThat(HelpDeepLinkIds.menu.map { routeForHelpTarget(it) }).containsNoDuplicates()
        for (id in HelpDeepLinkIds.all - HelpDeepLinkIds.menu) {
            assertWithMessage(id).that(routeForHelpTarget(id)).isNull()
        }
    }

    @Test
    fun `menu routes report their guide screen`() {
        assertThat(helpScreenIdFor("menu_home")).isEqualTo(HelpScreenIds.MENU)
        assertThat(helpScreenIdFor("menu_about")).isEqualTo(HelpScreenIds.MENU)
        assertThat(helpScreenIdFor("menu_settings_language")).isEqualTo(HelpScreenIds.SETTINGS)
        assertThat(helpScreenIdFor("menu_settings_sync")).isEqualTo(HelpScreenIds.SETTINGS)
        assertThat(helpScreenIdFor("menu_members")).isEqualTo(HelpScreenIds.MEMBERS)
        assertThat(helpScreenIdFor("menu_help?screen={screen}")).isEqualTo(HelpScreenIds.HELP)
        assertThat(helpScreenIdFor(null)).isNull()
    }
}
