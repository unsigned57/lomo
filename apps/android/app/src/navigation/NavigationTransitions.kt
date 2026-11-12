package com.lomo.app.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.material3.MotionScheme
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.NavBackStackEntry
import com.lomo.ui.theme.pageEnterTransition
import com.lomo.ui.theme.pageExitTransition

typealias NavEnterTransition =
    AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition

typealias NavExitTransition =
    AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition

/**
 * NavHost explicitly seeks these same pop transitions during predictive back. Shared-axis travel and fades
 * keep page geometry fixed across gesture navigation and button-driven back navigation.
 */
class NavigationTransitions(scheme: MotionScheme, layoutDirection: LayoutDirection, pageOffsetPx: Float) {
    private val forward = if (layoutDirection == LayoutDirection.Ltr) 1 else -1
    val standardEnter: NavEnterTransition = { pageEnterTransition(scheme, forward, pageOffsetPx) }
    val standardExit: NavExitTransition = { pageExitTransition(scheme, -forward, pageOffsetPx) }
    val standardPopEnter: NavEnterTransition = { pageEnterTransition(scheme, -forward, pageOffsetPx) }
    val standardPopExit: NavExitTransition = { pageExitTransition(scheme, forward, pageOffsetPx) }

    val imageViewerEnter: NavEnterTransition = {
        fadeIn(scheme.defaultEffectsSpec()) +
            scaleIn(initialScale = 0.92f, animationSpec = scheme.defaultSpatialSpec())
    }
    val imageViewerExit: NavExitTransition = { fadeOut(scheme.fastEffectsSpec()) }
    val imageViewerPopEnter: NavEnterTransition = { fadeIn(scheme.defaultEffectsSpec()) }
    val imageViewerPopExit: NavExitTransition = {
        fadeOut(scheme.fastEffectsSpec())
    }
    val searchEnter: NavEnterTransition = standardEnter
    val searchExit: NavExitTransition = standardExit
    val searchPopEnter: NavEnterTransition = standardPopEnter
    val searchPopExit: NavExitTransition = standardPopExit
}
