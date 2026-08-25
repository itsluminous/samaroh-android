package com.itsluminous.samaroh.feature.onboarding

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * §5 locale apply/persist: onboarding's language pick goes through
 * [DefaultLocaleApplier] → `LocaleManager` → `AppCompatDelegate.setApplicationLocales`,
 * whose value survives for the whole app session (AndroidX persists across process
 * restarts via `autoStoreLocales`, declared in the app manifest).
 */
@RunWith(RobolectricTestRunner::class)
class LocaleApplierTest {
    private val applier = DefaultLocaleApplier()

    @After
    fun resetLocale() {
        com.itsluminous.samaroh.core.i18n.LocaleManager
            .resetToSystemLocale()
    }

    @Test
    fun `v1 ships english and hindi`() {
        assertThat(applier.supportedLocales).containsExactly("en", "hi").inOrder()
    }

    @Test
    fun `applying hindi persists as the current app locale`() {
        applier.apply("hi")
        assertThat(applier.current()).isEqualTo("hi")
    }

    @Test
    fun `switching back to english replaces the override`() {
        applier.apply("hi")
        applier.apply("en")
        assertThat(applier.current()).isEqualTo("en")
    }

    @Test
    fun `no override means following the system locale`() {
        com.itsluminous.samaroh.core.i18n.LocaleManager
            .resetToSystemLocale()
        assertThat(applier.current()).isNull()
    }
}
