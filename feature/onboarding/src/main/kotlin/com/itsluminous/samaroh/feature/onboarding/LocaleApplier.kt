package com.itsluminous.samaroh.feature.onboarding

import com.itsluminous.samaroh.core.i18n.LocaleManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin seam over [LocaleManager] so the onboarding ViewModel is unit-testable on the JVM
 * (AppCompatDelegate needs an Android runtime).
 */
interface LocaleApplier {
    val supportedLocales: List<String>

    fun apply(languageTag: String)

    fun current(): String?
}

@Singleton
class DefaultLocaleApplier
    @Inject
    constructor() : LocaleApplier {
        override val supportedLocales: List<String> get() = LocaleManager.supportedLocales

        override fun apply(languageTag: String) = LocaleManager.setAppLocale(languageTag)

        override fun current(): String? = LocaleManager.currentAppLocale()
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class OnboardingModule {
    @Binds abstract fun bindLocaleApplier(impl: DefaultLocaleApplier): LocaleApplier
}
