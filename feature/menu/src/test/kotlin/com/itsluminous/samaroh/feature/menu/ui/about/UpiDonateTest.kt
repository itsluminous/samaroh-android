package com.itsluminous.samaroh.feature.menu.ui.about

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Donate-via-UPI deep link (§4.4 About): intent construction from the shared-catalog
 * URI (generated non-translatable resource) and the launch-first fallback contract —
 * no PackageManager pre-check, `ActivityNotFoundException` is the no-app signal.
 */
@RunWith(RobolectricTestRunner::class)
class UpiDonateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun catalogUri(): String = context.getString(com.itsluminous.samaroh.core.i18n.R.string.menu_about_donate_upi_uri)

    @Test
    fun `builds a upi pay view intent with payee name and note from the catalog uri`() {
        val intent = UpiDonate.intent(catalogUri())

        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.data?.scheme).isEqualTo("upi")
        assertThat(intent.data?.authority).isEqualTo("pay")
        assertThat(intent.data?.getQueryParameter("pa")).isEqualTo("electricprakash@axisb")
        assertThat(intent.data?.getQueryParameter("cn")).isEqualTo("Samaroh")
        assertThat(intent.data?.getQueryParameter("tn")).isEqualTo("Samaroh donation")
    }

    @Test
    fun `catalog uri is correctly percent-encoded`() {
        val uri = catalogUri()
        // The note carries a space; it must travel percent-encoded, never raw.
        assertThat(uri).doesNotContain(" ")
        assertThat(uri).contains("tn=Samaroh%20donation")
    }

    @Test
    fun `open returns false when no app claims upi links`() {
        // A bare Robolectric application has no UPI handler; checkActivities makes
        // startActivity throw ActivityNotFoundException like a real device would.
        shadowOf(context as Application).checkActivities(true)
        assertThat(UpiDonate.open(context, catalogUri())).isFalse()
    }

    @Test
    fun `open launches the upi intent without any resolve pre-check`() {
        val application = context as Application
        shadowOf(application).checkActivities(true)
        val handler = ComponentName("com.example.upiapp", "com.example.upiapp.PayActivity")
        shadowOf(context.packageManager).addActivityIfNotPresent(handler)
        shadowOf(context.packageManager).addIntentFilterForActivity(
            handler,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("upi")
            },
        )

        assertThat(UpiDonate.open(context, catalogUri())).isTrue()

        val fired = shadowOf(application).nextStartedActivity
        assertThat(fired.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(fired.data).isEqualTo(UpiDonate.intent(catalogUri()).data)
        // Launched from a non-Activity context, so the intent needs its own task.
        assertThat(fired.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    }
}
