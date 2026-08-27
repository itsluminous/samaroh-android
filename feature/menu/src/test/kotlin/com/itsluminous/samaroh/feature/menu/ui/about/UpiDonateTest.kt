package com.itsluminous.samaroh.feature.menu.ui.about

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Donate-via-UPI deep link construction + graceful no-app fallback (§4.4 About). */
@RunWith(RobolectricTestRunner::class)
class UpiDonateTest {
    @Test
    fun `builds a upi pay view intent with payee and name`() {
        val intent = UpiDonate.intent()

        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.data?.scheme).isEqualTo("upi")
        assertThat(intent.data?.authority).isEqualTo("pay")
        assertThat(intent.data?.getQueryParameter("pa")).isEqualTo("electricprakash@axisb")
        assertThat(intent.data?.getQueryParameter("cn")).isEqualTo("Samaroh")
    }

    @Test
    fun `open returns false when no app claims upi links`() {
        // A bare Robolectric application has no UPI handler installed.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertThat(UpiDonate.open(context, chooserTitle = "donate")).isFalse()
    }
}
