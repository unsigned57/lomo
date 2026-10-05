package com.lomo.app.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.navigation.NavBackStackEntry
import androidx.compose.material3.MotionScheme
import androidx.compose.ui.unit.LayoutDirection
import com.lomo.ui.theme.pageEnterTransition
import com.lomo.ui.theme.pageExitTransition

typealias NavEnterTransition =
    AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition

typealias NavExitTransition =
    AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition

class NavigationTransitions(scheme: MotionScheme, layoutDirection: LayoutDirection) {
    private val forward = if (layoutDirection == LayoutDirection.Ltr) 1 else -1
    val standardEnter: NavEnterTransition = { pageEnterTransition(scheme, forward) }
    val standardExit: NavExitTransition = { pageExitTransition(scheme, -forward) }
    val standardPopEnter: NavEnterTransition = { pageEnterTransition(scheme, -forward) }
    val standardPopExit: NavExitTransition = { pageExitTransition(scheme, forward) }

    val imageViewerEnter: NavEnterTransition = {
        fadeIn(scheme.defaultEffectsSpec()) +
            scaleIn(initialScale = 0.92f, animationSpec = scheme.defaultSpatialSpec())
    }
    val imageViewerExit: NavExitTransition = { fadeOut(scheme.fastEffectsSpec()) }
    val imageViewerPopEnter: NavEnterTransition = { fadeIn(scheme.defaultEffectsSpec()) }
    val imageViewerPopExit: NavExitTransition = {
        fadeOut(scheme.fastEffectsSpec()) +
            scaleOut(targetScale = 0.92f, animationSpec = scheme.defaultSpatialSpec())
    }
    val searchEnter: NavEnterTransition = { fadeIn(scheme.defaultEffectsSpec()) }
    val searchExit: NavExitTransition = { fadeOut(scheme.fastEffectsSpec()) }
    val searchPopEnter: NavEnterTransition = { fadeIn(scheme.defaultEffectsSpec()) }
    val searchPopExit: NavExitTransition = { fadeOut(scheme.fastEffectsSpec()) }
}
