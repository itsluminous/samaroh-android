package com.itsluminous.samaroh.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ADR-099 — the shell's root graph is built ONCE. A feature host (a destination with its
 * own inner `NavHostController` + ViewModel, exactly like the Booking/Expenses tabs) must
 * survive shell recompositions whose values the graph used to capture (sync badge, deep
 * link consumed, Menu pushed/popped …): the inner controller must not be re-created, the
 * destination nodes must stay the same instances, and the one-shot inputs the host
 * receives as [State]s must still be observed live.
 *
 * Shape mirrors `SamarohApp`: root NavHost with transitions, a plain tab destination hosting
 * an inner NavHost, a nested `navigation()` graph (the Menu), and a shell value that the
 * builder would capture. The same scenario with `NavHost(startDestination) { … }` capturing
 * the value replaces every destination node on every tick (measured while writing this test:
 * 5 ticks → 5 node instances, i.e. 5 `NavController.setGraph` re-syncs of the back stack).
 */
@RunWith(RobolectricTestRunner::class)
class StableNavGraphTest {
    @get:Rule
    val compose = createComposeRule()

    class HostViewModel : ViewModel()

    /** Mirrors a feature host: inner NavHostController + hiltViewModel-style ViewModel. */
    private class HostProbe {
        var creations = 0
        val controllers = HashSet<Int>()
        val viewModels = HashSet<Int>()
        var lastTarget: String? = null
    }

    @Composable
    private fun FeatureHost(
        probe: HostProbe,
        target: State<String?>,
    ) {
        val inner = rememberNavController()
        val vm: HostViewModel = viewModel()
        // One slot per host instance: `remember` runs again only when the host is re-created
        // (the remembered value is the creation ordinal — lint forbids remembering Unit).
        val creation =
            remember {
                probe.controllers.add(System.identityHashCode(inner))
                probe.viewModels.add(System.identityHashCode(vm))
                ++probe.creations
            }
        check(creation > 0)
        probe.lastTarget = target.value
        NavHost(navController = inner, startDestination = "inner") {
            composable("inner") { Text("inner ${probe.creations}") }
        }
    }

    private var rootController: NavHostController? = null

    @Composable
    private fun Shell(
        shellValue: Int,
        probe: HostProbe,
        target: State<String?>,
    ) {
        val navController = rememberNavController()
        rootController = navController
        // Like SamarohApp: values the graph must see later become States first …
        val shellValueState = rememberUpdatedState(shellValue)
        Column {
            Text("badge $shellValue")
            // … and the builder captures only stable references (States, the probe).
            val graph =
                rememberStableNavGraph(navController, "feature") {
                    composable("feature") { FeatureHost(probe, target) }
                    navigation(route = "menu_tab", startDestination = "menu") {
                        composable("menu") { Text("menu ${shellValueState.value}") }
                    }
                }
            NavHost(
                navController = navController,
                graph = graph,
                enterTransition = { fadeIn() },
                exitTransition = { fadeOut() },
                popEnterTransition = { fadeIn() },
                popExitTransition = { fadeOut() },
            )
        }
    }

    @Test
    fun `shell value changes keep the feature host, its inner controller and the graph nodes`() {
        val probe = HostProbe()
        var shellValue by mutableStateOf(0)
        val target = mutableStateOf<String?>(null)
        compose.setContent { Shell(shellValue, probe, target) }
        compose.onNodeWithText("inner 1").assertExists()

        val featureNodes = HashSet<Int>()
        repeat(5) {
            shellValue++
            compose.waitForIdle()
            featureNodes.add(System.identityHashCode(rootController!!.graph.findNode("feature")!!))
        }
        compose.onNodeWithText("badge 5").assertExists()

        // The one-shot input is still observed live through the State …
        target.value = "party-42"
        compose.waitForIdle()
        assertThat(probe.lastTarget).isEqualTo("party-42")

        // … the graph's nodes were never replaced …
        assertThat(featureNodes).hasSize(1)
        // … and the host itself was composed exactly once: same inner controller, same VM.
        assertThat(probe.creations).isEqualTo(1)
        assertThat(probe.controllers).hasSize(1)
        assertThat(probe.viewModels).hasSize(1)
        compose.onNodeWithText("inner 1").assertExists()
    }

    @Test
    fun `nested graph content reads shell values live and the graph survives a push and pop`() {
        val probe = HostProbe()
        var shellValue by mutableStateOf(0)
        compose.setContent { Shell(shellValue, probe, mutableStateOf(null)) }
        val nodeBefore = System.identityHashCode(rootController!!.graph.findNode("feature")!!)
        // Push the Menu over the tab (what the kebab does): `menuHasParent` flips in the shell.
        rootController!!.navigate("menu_tab")
        compose.waitForIdle()
        shellValue = 7
        compose.waitForIdle()
        compose.onNodeWithText("menu 7").assertExists()
        rootController!!.popBackStack()
        compose.waitForIdle()
        compose.onNodeWithText("badge 7").assertExists()
        assertThat(System.identityHashCode(rootController!!.graph.findNode("feature")!!)).isEqualTo(nodeBefore)
    }
}
