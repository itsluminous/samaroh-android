package com.itsluminous.samaroh

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Instrumentation runner for the e2e suite: swaps [SamarohApplication] for Hilt's
 * [HiltTestApplication] so `@HiltAndroidTest` classes get an injectable test component
 * (the production app class carries no behavior beyond `@HiltAndroidApp`).
 */
@Suppress("unused") // referenced from app/build.gradle.kts testInstrumentationRunner
class SamarohTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        name: String?,
        context: Context?,
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
