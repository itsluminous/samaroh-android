package com.itsluminous.samaroh.feature.menu.ui.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ADR-075 filter behavior: normalized-substring matching over BOTH locales' texts,
 * live keyword hits, owner/session gating, and the blank-query → normal-menu contract.
 */
@RunWith(RobolectricTestRunner::class)
class MenuSearchFilterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resolved = MenuSearch.resolve(context)

    private fun ids(
        query: String,
        isOwner: Boolean = true,
        isSignedIn: Boolean = true,
    ): List<String> = MenuSearch.filter(resolved, query, isOwner, isSignedIn).map { it.entry.id }

    @Test
    fun `quality finds the image-quality row`() {
        assertThat(ids("quality")).contains("image_quality")
    }

    @Test
    fun `backup finds the backup section`() {
        assertThat(ids("backup")).contains("backup")
    }

    @Test
    fun `matching is case-insensitive`() {
        assertThat(ids("QUALITY")).contains("image_quality")
    }

    @Test
    fun `hindi query matches even while the test locale is english`() {
        // "भाषा" is the Hindi Settings title of the Language row.
        assertThat(ids("भाषा")).contains("language")
    }

    @Test
    fun `keywords match - a built-in event-type name finds the event-types screen`() {
        assertThat(ids("wedding")).contains("event_types")
    }

    @Test
    fun `every query token must match - a nonsense extra token empties the result`() {
        assertThat(ids("backup zzzznope")).isEmpty()
    }

    @Test
    fun `blank query returns nothing - the screen shows the normal menu instead`() {
        assertThat(ids("")).isEmpty()
        assertThat(ids("   ")).isEmpty()
    }

    @Test
    fun `report names resolve to report deep links`() {
        val revenue = MenuSearch.filter(resolved, "revenue", isOwner = true, isSignedIn = true)
        assertThat(revenue.map { it.entry.id }).contains("report_revenue")
        val target = revenue.first { it.entry.id == "report_revenue" }.entry.target
        assertThat(target).isEqualTo(MenuSearchTarget.Report("revenue"))
    }

    @Test
    fun `owner-only destinations hide for non-owners`() {
        assertThat(ids("backup", isOwner = false)).doesNotContain("backup")
        assertThat(ids("members", isOwner = false)).doesNotContain("members")
    }

    @Test
    fun `sign-out hides when not signed in`() {
        assertThat(ids("sign", isSignedIn = true)).contains("sign_out")
        assertThat(ids("sign", isSignedIn = false)).doesNotContain("sign_out")
    }

    @Test
    fun `no result for unknown text`() {
        assertThat(ids("qqqqqqq")).isEmpty()
    }
}
