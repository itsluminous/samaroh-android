package com.itsluminous.samaroh.core.help

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The bundled guide (ADR-108): `help/guide.json` + `help/routes.json` copied from the
 * shared submodule into this module's assets by `generateHelpAssets`. Parsed lazily, once
 * per process (≈110 KB, a few ms); [preload] warms it off the main thread so the first
 * `onProvideAssistContent` / Help open never parses on the UI thread.
 */
@Singleton
class HelpGuideSource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val parsedGuide: HelpGuide by lazy {
            HelpGuide.parse(
                context.assets
                    .open(GUIDE_ASSET)
                    .bufferedReader()
                    .use { it.readText() },
            )
        }

        private val parsedRoutes: HelpRoutes by lazy {
            HelpRoutes.parse(
                context.assets
                    .open(ROUTES_ASSET)
                    .bufferedReader()
                    .use { it.readText() },
            )
        }

        val guide: HelpGuide get() = parsedGuide

        val routes: HelpRoutes get() = parsedRoutes

        /** Parses the guide on IO if it has not been parsed yet (fire-and-forget). */
        fun preload(scope: CoroutineScope) {
            scope.launch(Dispatchers.IO) { runCatching { parsedGuide } }
        }

        companion object {
            const val GUIDE_ASSET = "help/guide.json"
            const val ROUTES_ASSET = "help/routes.json"
        }
    }
