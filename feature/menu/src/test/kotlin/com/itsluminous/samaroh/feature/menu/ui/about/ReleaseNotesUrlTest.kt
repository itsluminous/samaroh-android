package com.itsluminous.samaroh.feature.menu.ui.about

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * About-screen version row (§4.4): the release-notes URL derives from the shared-catalog
 * source URL + the build's `versionName` (releases are tagged `v<versionName>`), so the
 * row always opens THIS build's release with zero per-release edits.
 */
@RunWith(RobolectricTestRunner::class)
class ReleaseNotesUrlTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun catalogSourceUrl(): String = context.getString(com.itsluminous.samaroh.core.i18n.R.string.menu_about_source_code_url)

    @Test
    fun `derives the tag url from the catalog source url and a version name`() {
        assertThat(releaseNotesUrl(catalogSourceUrl(), "0.5.2"))
            .isEqualTo("https://github.com/itsluminous/samaroh-android/releases/tag/v0.5.2")
    }

    @Test
    fun `a trailing slash on the source url does not double up`() {
        assertThat(releaseNotesUrl("https://example.com/repo/", "1.2.3"))
            .isEqualTo("https://example.com/repo/releases/tag/v1.2.3")
    }

    @Test
    fun `blank version falls back to the releases list instead of a broken tag url`() {
        assertThat(releaseNotesUrl(catalogSourceUrl(), ""))
            .isEqualTo("https://github.com/itsluminous/samaroh-android/releases")
    }
}
