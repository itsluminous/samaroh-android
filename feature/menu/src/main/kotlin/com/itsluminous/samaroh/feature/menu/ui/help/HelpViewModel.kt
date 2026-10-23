package com.itsluminous.samaroh.feature.menu.ui.help

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.samaroh.core.auth.PermissionGuard
import com.itsluminous.samaroh.core.data.navigation.HelpNavigationHolder
import com.itsluminous.samaroh.core.data.navigation.HelpTarget
import com.itsluminous.samaroh.core.data.session.ActiveBusinessProvider
import com.itsluminous.samaroh.core.data.session.CurrentUserProvider
import com.itsluminous.samaroh.core.help.GrantedPermissions
import com.itsluminous.samaroh.core.help.HelpGuide
import com.itsluminous.samaroh.core.help.HelpGuideSource
import com.itsluminous.samaroh.core.help.HelpScreen
import com.itsluminous.samaroh.core.help.HelpTask
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** The Help screen's state (ADR-108). */
data class HelpUiState(
    /** Guide parsed and grants known — null while loading (the screen shows nothing yet). */
    val screens: List<HelpScreen>? = null,
    val query: String = "",
    /** The screen's section the list should open on (the `?` affordance), or null. */
    val initialScreenId: String? = null,
) {
    /** Whether the list shows search results rather than the full guide. */
    val searching: Boolean get() = query.isNotBlank()
}

/**
 * In-app Help (ADR-108): the bundled guide filtered to Android + this member's grants
 * (hide-not-grey), live search, and "Show me" — which parks the task's deep link in the
 * app-wide [HelpNavigationHolder] for the shell/feature hosts to land on.
 *
 * Grants follow the shell's rule (`MainViewModel.shareContext`): no business or no
 * session → owner mode (everything); otherwise the member's permissions, live.
 */
@HiltViewModel
class HelpViewModel
    @Inject
    constructor(
        private val guideSource: HelpGuideSource,
        activeBusinessProvider: ActiveBusinessProvider,
        currentUserProvider: CurrentUserProvider,
        permissionGuard: PermissionGuard,
        private val helpNavigation: HelpNavigationHolder,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val query = MutableStateFlow("")

        /** `?screen=` nav argument: the guide screen to open on (blank = top of the list). */
        private val initialScreenId: String? = savedStateHandle.get<String>(SCREEN_ARG)?.takeIf { it.isNotBlank() }

        /** Parsed off the main thread the first time (cached in the singleton afterwards). */
        private val guide: Flow<HelpGuide> = flow { emit(guideSource.guide) }.flowOn(Dispatchers.IO)

        @OptIn(ExperimentalCoroutinesApi::class)
        private val granted: Flow<GrantedPermissions> =
            combine(currentUserProvider.currentUserId, activeBusinessProvider.activeBusiness) { userId, business -> userId to business }
                .flatMapLatest { (userId, business) ->
                    if (userId == null || business == null) {
                        flowOf(GrantedPermissions.OWNER)
                    } else {
                        combine(permissionGuard.permissions(business.id), permissionGuard.isOwner(business.id)) { permissions, isOwner ->
                            GrantedPermissions.of(isOwner, permissions)
                        }
                    }
                }

        val uiState: StateFlow<HelpUiState> =
            combine(guide, granted, query) { guide, granted, query ->
                HelpUiState(
                    screens = HelpCatalog.search(HelpCatalog.visible(guide, granted), query),
                    query = query,
                    initialScreenId = initialScreenId,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HelpUiState(initialScreenId = initialScreenId))

        /** The public help page of [screen] in [lang] — what the share action sends. */
        fun pageUrl(
            screen: HelpScreen,
            lang: String,
        ): String = guideSource.guide.pageUrl(lang, screen.id)

        fun onQueryChange(value: String) {
            query.value = value
        }

        /** "Show me": the shell switches to the owning tab and the feature host lands on the route. */
        fun showMe(task: HelpTask) {
            helpNavigation.set(HelpTarget(task.deepLink))
        }

        companion object {
            const val SCREEN_ARG = "screen"
        }
    }
