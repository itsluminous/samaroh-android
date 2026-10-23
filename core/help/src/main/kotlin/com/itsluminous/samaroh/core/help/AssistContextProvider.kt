package com.itsluminous.samaroh.core.help

import com.itsluminous.samaroh.core.data.navigation.CurrentScreenTracker
import com.itsluminous.samaroh.core.model.MemberPermissions
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the assistant context for what is on screen NOW (ADR-108): reads
 * [CurrentScreenTracker] (the feature hosts keep it current), the app locale and the
 * member's grants, and returns the memoised [AssistContext] for that combination. The
 * guide is parsed once per process ([HelpGuideSource]); a payload is built once per
 * (screen, language, grants) and reused — `onProvideAssistContent` runs on the main thread
 * and must stay cheap.
 */
@Singleton
class AssistContextProvider
    @Inject
    constructor(
        private val guideSource: HelpGuideSource,
    ) {
        private data class Key(
            val screenId: String?,
            val lang: String,
            val isOwner: Boolean,
            val permissions: MemberPermissions?,
        )

        private val cache = ConcurrentHashMap<Key, AssistContext>()

        /**
         * The context for the tracker's current screen.
         *
         * @param appLocale the locale the app RENDERS in (the activity's resources), not the device's.
         * @param isOwner owner / signed-out owner mode → every task; else [permissions] gate tasks.
         */
        fun current(
            appLocale: Locale,
            isOwner: Boolean = true,
            permissions: MemberPermissions? = null,
        ): AssistContext = forScreen(CurrentScreenTracker.state.value.effective, appLocale, isOwner, permissions)

        fun forScreen(
            screenId: String?,
            appLocale: Locale,
            isOwner: Boolean = true,
            permissions: MemberPermissions? = null,
        ): AssistContext {
            val lang = HelpLanguage.of(appLocale)
            val key = Key(screenId, lang, isOwner, if (isOwner) null else permissions)
            return cache.getOrPut(key) {
                val granted = if (isOwner || permissions == null) GrantedPermissions.OWNER else GrantedPermissions.of(false, permissions)
                AssistContextBuilder.build(guideSource.guide, screenId, lang, granted)
            }
        }
    }
