package com.itsluminous.samaroh.core.help

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.samaroh.core.data.navigation.HelpScreenIds
import com.itsluminous.samaroh.core.model.BookingPermissions
import com.itsluminous.samaroh.core.model.MemberPermissions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * The assistant payload (ADR-108 / `shared/docs/assistant-context.md`): URL + JSON-LD per
 * screen and language, index fallback, permission filtering, the 8 KB cap with its
 * deterministic truncation order, and the no-PII guarantee (the payload is built from the
 * public guide only — asserted by feeding fixture user data and grepping).
 */
class AssistContextBuilderTest {
    private val guide = SharedHelpFiles.guide()
    private val json = Json

    private fun jsonLd(context: AssistContext) = json.parseToJsonElement(context.jsonLd).jsonObject

    @Test
    fun `booking card in hindi points at the hi page and carries the hindi tasks`() {
        val context = AssistContextBuilder.build(guide, HelpScreenIds.BOOKING_CARD, "hi")
        assertThat(context.pageUrl).isEqualTo("${guide.site.baseUrl}hi/booking_card.html")
        val root = jsonLd(context)
        assertThat(root["@context"]!!.jsonPrimitive.content).isEqualTo("https://schema.org")
        assertThat(root["@type"]!!.jsonPrimitive.content).isEqualTo("WebPage")
        assertThat(root["inLanguage"]!!.jsonPrimitive.content).isEqualTo("hi")
        assertThat(root["url"]!!.jsonPrimitive.content).isEqualTo(context.pageUrl)
        val screen = guide.screen(HelpScreenIds.BOOKING_CARD)!!
        assertThat(root["name"]!!.jsonPrimitive.content).isEqualTo(screen.title.hi)
        assertThat(root["description"]!!.jsonPrimitive.content).isEqualTo(screen.summary.hi)
        val samaroh = root["samaroh"]!!.jsonObject
        assertThat(samaroh["screenId"]!!.jsonPrimitive.content).isEqualTo("booking_card")
        assertThat(samaroh["language"]!!.jsonPrimitive.content).isEqualTo("hi")
        assertThat(samaroh["app"]!!.jsonPrimitive.content).isEqualTo("android")
        assertThat(samaroh["guideIndexUrl"]!!.jsonPrimitive.content).isEqualTo("${guide.site.baseUrl}hi/index.html")
        val tasks = samaroh["tasks"]!!.jsonArray
        assertThat(
            tasks.map { it.jsonObject["id"]!!.jsonPrimitive.content },
        ).containsExactlyElementsIn(screen.tasks.map { it.id }).inOrder()
        val recordPayment = tasks.first { it.jsonObject["id"]!!.jsonPrimitive.content == "record_payment" }.jsonObject
        val guideTask = screen.tasks.first { it.id == "record_payment" }
        assertThat(recordPayment["title"]!!.jsonPrimitive.content).isEqualTo(guideTask.title.hi)
        assertThat(recordPayment["steps"]!!.jsonArray.map { it.jsonPrimitive.content })
            .containsExactlyElementsIn(guideTask.steps.filter { it.appliesTo("android") }.map { it.hi })
            .inOrder()
        // Compact: no pretty printing.
        assertThat(context.jsonLd).doesNotContain("\n")
    }

    @Test
    fun `language switch flips url and texts to english`() {
        val hi = AssistContextBuilder.build(guide, HelpScreenIds.RECORD_PAYMENT, "hi")
        val en = AssistContextBuilder.build(guide, HelpScreenIds.RECORD_PAYMENT, "en")
        assertThat(en.pageUrl).isEqualTo("${guide.site.baseUrl}en/record_payment.html")
        assertThat(hi.pageUrl).isEqualTo("${guide.site.baseUrl}hi/record_payment.html")
        val screen = guide.screen(HelpScreenIds.RECORD_PAYMENT)!!
        assertThat(jsonLd(en)["name"]!!.jsonPrimitive.content).isEqualTo(screen.title.en)
        assertThat(jsonLd(hi)["name"]!!.jsonPrimitive.content).isEqualTo(screen.title.hi)
        assertThat(jsonLd(en)["samaroh"]!!.jsonObject["guideIndexUrl"]!!.jsonPrimitive.content).endsWith("/en/index.html")
    }

    @Test
    fun `every guide screen builds in both languages within the cap`() {
        for (screenId in HelpScreenIds.all) {
            for (lang in listOf("en", "hi")) {
                val context = AssistContextBuilder.build(guide, screenId, lang)
                assertWithMessage("$screenId/$lang").that(context.jsonLd.length).isAtMost(AssistContextBuilder.MAX_JSON_LD_CHARS)
                assertWithMessage("$screenId/$lang").that(context.pageUrl).isEqualTo(guide.pageUrl(lang, screenId))
                assertThat(jsonLd(context)["samaroh"]!!.jsonObject["screenId"]!!.jsonPrimitive.content).isEqualTo(screenId)
            }
        }
    }

    @Test
    fun `unknown or null screen falls back to the index of the current language`() {
        for (screenId in listOf(null, "debug_screen", HelpScreenIds.HELP)) {
            val context = AssistContextBuilder.build(guide, screenId, "hi")
            assertThat(context.pageUrl).isEqualTo("${guide.site.baseUrl}hi/index.html")
            val root = jsonLd(context)
            assertThat(root["url"]!!.jsonPrimitive.content).isEqualTo(context.pageUrl)
            assertThat(root.containsKey("name")).isFalse()
            val samaroh = root["samaroh"]!!.jsonObject
            assertThat(samaroh["screenId"]!!.jsonPrimitive.isString).isFalse()
            assertThat(samaroh["tasks"]!!.jsonArray).isEmpty()
            assertThat(samaroh["language"]!!.jsonPrimitive.content).isEqualTo("hi")
        }
    }

    @Test
    fun `tasks the member cannot perform are omitted`() {
        val viewer = GrantedPermissions.of(isOwner = false, permissions = MemberPermissions.viewer())
        val context = AssistContextBuilder.build(guide, HelpScreenIds.BOOKING_CARD, "en", viewer)
        val ids = jsonLd(context)["samaroh"]!!.jsonObject["tasks"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertThat(ids).doesNotContain("record_payment")
        assertThat(ids).doesNotContain("share_invoice")
        assertThat(ids).contains("call_customer")
        assertThat(ids).contains("whatsapp_reminder")
    }

    @Test
    fun `a screen the member cannot view falls back to the index`() {
        val noBooking = GrantedPermissions.of(isOwner = false, permissions = MemberPermissions())
        val context = AssistContextBuilder.build(guide, HelpScreenIds.BOOKING_CALENDAR, "en", noBooking)
        assertThat(context.pageUrl).isEqualTo(guide.indexUrl("en"))
    }

    @Test
    fun `size cap drops step text from the last task first, then whole tasks, never the envelope`() {
        val longStep = "x".repeat(600)
        val screen = guide.screen(HelpScreenIds.BOOKING_CARD)!!
        val inflated =
            screen.copy(
                tasks =
                    screen.tasks.map { task ->
                        task.copy(steps = task.steps.map { it.copy(en = longStep, hi = longStep) })
                    },
            )
        val fat = guide.copy(screens = listOf(inflated))
        val context = AssistContextBuilder.build(fat, HelpScreenIds.BOOKING_CARD, "en")
        assertThat(context.jsonLd.length).isAtMost(AssistContextBuilder.MAX_JSON_LD_CHARS)
        val root = jsonLd(context)
        assertThat(root["name"]!!.jsonPrimitive.content).isEqualTo(screen.title.en)
        assertThat(root["description"]!!.jsonPrimitive.content).isEqualTo(screen.summary.en)
        val tasks = root["samaroh"]!!.jsonObject["tasks"]!!.jsonArray
        // Every task id survives (only steps were dropped), and the surviving steps are at the FRONT.
        assertThat(
            tasks.map { it.jsonObject["id"]!!.jsonPrimitive.content },
        ).containsExactlyElementsIn(screen.tasks.map { it.id }).inOrder()
        val stepCounts = tasks.map { it.jsonObject["steps"]!!.jsonArray.size }
        val firstEmpty = stepCounts.indexOfFirst { it == 0 }
        assertThat(firstEmpty).isAtLeast(1)
        assertThat(stepCounts.drop(firstEmpty).all { it == 0 }).isTrue()
        assertThat(stepCounts.take(firstEmpty).all { it > 0 }).isTrue()
    }

    @Test
    fun `size cap drops whole tasks once no task has steps`() {
        val screen = guide.screen(HelpScreenIds.BOOKING_CARD)!!
        val longTitle = "t".repeat(2_500)
        val inflated = screen.copy(tasks = screen.tasks.map { task -> task.copy(title = LocalizedText(longTitle, longTitle)) })
        val context = AssistContextBuilder.build(guide.copy(screens = listOf(inflated)), HelpScreenIds.BOOKING_CARD, "en")
        assertThat(context.jsonLd.length).isAtMost(AssistContextBuilder.MAX_JSON_LD_CHARS)
        val tasks = jsonLd(context)["samaroh"]!!.jsonObject["tasks"]!!.jsonArray
        assertThat(tasks.size).isLessThan(screen.tasks.size)
        assertThat(tasks.size).isAtLeast(1)
        // The ones kept are the FIRST tasks of the screen.
        assertThat(tasks.map { it.jsonObject["id"]!!.jsonPrimitive.content }).isEqualTo(screen.tasks.map { it.id }.take(tasks.size))
    }

    @Test
    fun `payload never contains user data - only guide text, ids and urls`() {
        // The builder's only inputs are the guide, a screen id and a language; these fixture
        // values stand for a booking on screen and must be absent by construction.
        val customerName = "Meera Sharma"
        val phone = "9876543210"
        val amount = "2,00,000"
        val bookingId = "bkg_7f3a9c"
        val businessName = "Sunrise Gardens"
        for (screenId in HelpScreenIds.all) {
            for (lang in listOf("en", "hi")) {
                val payload = AssistContextBuilder.build(guide, screenId, lang).jsonLd
                for (secret in listOf(customerName, phone, amount, bookingId, businessName)) {
                    assertWithMessage("$screenId/$lang leaks '$secret'").that(payload).doesNotContain(secret)
                }
                // No field names that would ever carry record data.
                for (field in listOf("customer", "phone", "amount", "bookingId", "partyId", "businessId", "email")) {
                    assertWithMessage("$screenId/$lang has field '$field'").that(payload).doesNotContain("\"$field\"")
                }
            }
        }
    }

    @Test
    fun `owner default sees record payment while a plain viewer does not`() {
        val owner = AssistContextBuilder.build(guide, HelpScreenIds.BOOKING_CARD, "en")
        assertThat(owner.jsonLd).contains("\"record_payment\"")
        val staffNoPayments =
            GrantedPermissions.of(false, MemberPermissions(booking = BookingPermissions(view = true, create = true)))
        val staff = AssistContextBuilder.build(guide, HelpScreenIds.BOOKING_CARD, "en", staffNoPayments)
        assertThat(staff.jsonLd).doesNotContain("\"record_payment\"")
    }
}
