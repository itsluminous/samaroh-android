package com.itsluminous.samaroh.core.designsystem.theme

import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset

/**
 * The single motion spec for the whole app (§6 polish): every navigation transition,
 * list-item animation and content change uses these durations and easings so motion feels
 * consistent across tabs. All helpers degrade to no/instant motion when the user has
 * animations disabled system-wide (reduced motion) — see [rememberReducedMotion].
 */
object SamarohMotion {
    /** Quick exits and small state changes. */
    const val DURATION_SHORT_MS = 150

    /** Standard enters and content transitions. */
    const val DURATION_MEDIUM_MS = 300

    /** Large-surface transitions (month grid swaps). */
    const val DURATION_LONG_MS = 400

    /** Emphasized-decelerate: incoming content settles gently. */
    val EnterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Emphasized-accelerate: outgoing content leaves quickly. */
    val ExitEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Standard easing for in-place movement (list reordering, size changes). */
    val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun <T> enterSpec(durationMs: Int = DURATION_MEDIUM_MS): FiniteAnimationSpec<T> = tween(durationMs, easing = EnterEasing)

    fun <T> exitSpec(durationMs: Int = DURATION_SHORT_MS): FiniteAnimationSpec<T> = tween(durationMs, easing = ExitEasing)

    fun <T> standardSpec(durationMs: Int = DURATION_MEDIUM_MS): FiniteAnimationSpec<T> = tween(durationMs, easing = StandardEasing)

    /** Destination enter: fade-through with a slight scale settle. */
    fun screenEnter(reducedMotion: Boolean): EnterTransition =
        if (reducedMotion) {
            EnterTransition.None
        } else {
            fadeIn(enterSpec()) + scaleIn(initialScale = 0.92f, animationSpec = enterSpec())
        }

    /** Destination exit: quick fade so the incoming screen owns the motion. */
    fun screenExit(reducedMotion: Boolean): ExitTransition = if (reducedMotion) ExitTransition.None else fadeOut(exitSpec())

    /**
     * Horizontal content-swap enter (paging content like the calendar month grid).
     * [towardStart] slides new content in from the end edge (forward navigation).
     */
    fun slideEnter(
        reducedMotion: Boolean,
        towardStart: Boolean,
    ): EnterTransition =
        if (reducedMotion) {
            EnterTransition.None
        } else {
            slideInHorizontally(enterSpec()) { full -> if (towardStart) full else -full } + fadeIn(enterSpec())
        }

    /** Horizontal content-swap exit paired with [slideEnter]. */
    fun slideExit(
        reducedMotion: Boolean,
        towardStart: Boolean,
    ): ExitTransition =
        if (reducedMotion) {
            ExitTransition.None
        } else {
            slideOutHorizontally(exitSpec(DURATION_MEDIUM_MS)) { full -> if (towardStart) -full else full } + fadeOut(exitSpec())
        }
}

/**
 * True when the user has disabled animations system-wide (animator duration scale = 0),
 * the platform's reduced-motion signal. All [SamarohMotion] consumers must honor it.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}

/**
 * The one sanctioned list-item animation: items fade in, fade out and glide to their new
 * position on reorder/filter. No-op under reduced motion. Use on every `items {}` row.
 */
@Composable
fun LazyItemScope.animatedListItem(modifier: Modifier = Modifier): Modifier {
    val reducedMotion = rememberReducedMotion()
    return if (reducedMotion) {
        modifier
    } else {
        modifier.animateItem(
            fadeInSpec = SamarohMotion.enterSpec(),
            placementSpec = SamarohMotion.standardSpec<IntOffset>(),
            fadeOutSpec = SamarohMotion.exitSpec(),
        )
    }
}
