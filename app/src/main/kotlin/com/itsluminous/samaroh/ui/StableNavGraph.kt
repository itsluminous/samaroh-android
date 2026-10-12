package com.itsluminous.samaroh.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavGraph
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.createGraph

/**
 * Builds the shell's root [NavGraph] exactly ONCE per ([navController], [startDestination])
 * (ADR-099).
 *
 * `NavHost(navController, startDestination) { … }` remembers its graph keyed on the builder
 * lambda; a lambda that captures shell state (`pendingAppLink`, share flags, the sync badge's
 * recomposition …) is a new instance on every recomposition, so every shell recomposition
 * produced a new graph, `NavController.setGraph` re-synced the back stack in place and
 * navigation-compose re-created each feature host — inner nav stack and ViewModels lost
 * (found while shipping ADR-098). Here the builder runs once; anything a feature graph must
 * read later is handed to it as a [androidx.compose.runtime.State] (or a holder such as
 * `BookingDeepLinkHolder`) that its destination content reads at composition time.
 *
 * [builder] MUST therefore capture only stable references (the controller, ViewModels,
 * `State`s, holders); a plain value captured here is frozen at first composition.
 */
@Composable
internal fun rememberStableNavGraph(
    navController: NavHostController,
    startDestination: String,
    builder: NavGraphBuilder.() -> Unit,
): NavGraph =
    remember(navController, startDestination) {
        navController.createGraph(startDestination = startDestination, builder = builder)
    }
