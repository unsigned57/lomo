package com.lomo.app.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry
import com.lomo.ui.theme.MotionTokens

typealias NavEnterTransition =
    AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition

typealias NavExitTransition =
    AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition

object NavigationTransitions {
    val standardEnter: NavEnterTransition = {
        slideInHorizontally(
            initialOffsetX = { (it * 0.15f).toInt() },
            animationSpec =
                tween(
                    durationMillis = MotionTokens.DurationLong2,
                    easing = MotionTokens.EasingEmphasizedDecelerate,
                ),
        ) +
            fadeIn(
                animationSpec =
                    tween(
                        durationMillis = MotionTokens.DurationLong2,
                    ),
            ) +
            scaleIn(
                initialScale = 0.95f,
                animationSpec =
                    tween(
                        durationMillis = MotionTokens.DurationLong2,
                        easing = MotionTokens.EasingEmphasizedDecelerate,
                    ),
            )
    }

    val standardExit: NavExitTransition = {
        slideOutHorizontally(
            targetOffsetX = { -(it * 0.15f).toInt() },
            animationSpec =
                tween(
                    durationMillis = MotionTokens.DurationLong2,
                    easing = MotionTokens.EasingEmphasizedAccelerate,
                ),
        ) +
            fadeOut(
                animationSpec =
                    tween(
                        durationMillis = MotionTokens.DurationLong2,
                    ),
            ) +
            scaleOut(
                targetScale = 1.05f,
                animationSpec =
                    tween(
                        durationMillis = MotionTokens.DurationLong2,
                        easing = MotionTokens.EasingEmphasizedAccelerate,
                    ),
            )
    }

    val standardPopEnter: NavEnterTransition = {
        slideInHorizontally(
            initialOffsetX = { -(it * 0.15f).toInt() },
            animationSpec =
                tween(
                    durationMillis = MotionTokens.DurationLong2,
                    easing = MotionTokens.EasingEmphasizedDecelerate,
                ),
        ) +
            fadeIn(
                animationSpec =
                    tween(
                        durationMillis = MotionTokens.DurationLong2,
                    ),
            ) +
            scaleIn(
                initialScale = 1.05f,
                animationSpec =
                    tween(
                        durationMillis = MotionTokens.DurationLong2,
                        easing = MotionTokens.EasingEmphasizedDecelerate,
                    ),
            )
    }

    val standardPopExit: NavExitTransition = {
        slideOutHorizontally(
            targetOffsetX = { (it * 0.15f).toInt() },
            animationSpec =
                tween(
                    durationMillis = MotionTokens.DurationLong2,
                    easing = MotionTokens.EasingEmphasizedAccelerate,
                ),
        ) +
            fadeOut(
                animationSpec =
                    tween(
                        durationMillis = MotionTokens.DurationLong2,
                    ),
            ) +
            scaleOut(
                targetScale = 0.95f,
                animationSpec =
                    tween(
                        durationMillis = MotionTokens.DurationLong2,
                        easing = MotionTokens.EasingEmphasizedAccelerate,
                    ),
            )
    }

    val imageViewerEnter: NavEnterTransition = {
        fadeIn(
            animationSpec =
                tween(
                    durationMillis = MotionTokens.DurationMedium2,
                    easing = MotionTokens.EasingEmphasizedDecelerate,
                ),
        ) +
            scaleIn(
                initialScale = 0.92f,
                animationSpec =
                    tween(
                        durationMillis = MotionTokens.DurationMedium2,
                        easing = MotionTokens.EasingEmphasizedDecelerate,
                    ),
            )
    }

    val imageViewerExit: NavExitTransition = {
        fadeOut(
            animationSpec =
                tween(
                    durationMillis = MotionTokens.DurationShort4,
                    easing = MotionTokens.EasingEmphasizedAccelerate,
                ),
        ) +
            scaleOut(
                targetScale = 1.05f,
                animationSpec =
                    tween(
                        durationMillis = MotionTokens.DurationShort4,
                        easing = MotionTokens.EasingEmphasizedAccelerate,
                    ),
            )
    }

    val imageViewerPopEnter: NavEnterTransition = {
        fadeIn(
            animationSpec =
                tween(
                    durationMillis = MotionTokens.DurationMedium1,
                ),
        )
    }

    val imageViewerPopExit: NavExitTransition = {
        fadeOut(
            animationSpec =
                tween(
                    durationMillis = MotionTokens.DurationMedium2,
                    easing = MotionTokens.EasingEmphasizedAccelerate,
                ),
        ) +
            scaleOut(
                targetScale = 0.92f,
                animationSpec =
                    tween(
                        durationMillis = MotionTokens.DurationMedium2,
                        easing = MotionTokens.EasingEmphasizedAccelerate,
                    ),
            )
    }

    val searchEnter: NavEnterTransition = {
        fadeIn(
            animationSpec =
                tween(
                    durationMillis = MotionTokens.DurationMedium2,
                    easing = MotionTokens.EasingEmphasizedDecelerate,
                ),
        ) +
            scaleIn(
                initialScale = SEARCH_INITIAL_SCALE,
                animationSpec =
                    tween(
                        durationMillis = MotionTokens.DurationMedium2,
                        easing = MotionTokens.EasingEmphasizedDecelerate,
                    ),
            )
    }

    val searchExit: NavExitTransition = {
        fadeOut(
            animationSpec =
                tween(
                    durationMillis = MotionTokens.DurationShort4,
                    easing = MotionTokens.EasingEmphasizedAccelerate,
                ),
        ) +
            scaleOut(
                targetScale = SEARCH_INITIAL_SCALE,
                animationSpec =
                    tween(
                        durationMillis = MotionTokens.DurationShort4,
                        easing = MotionTokens.EasingEmphasizedAccelerate,
                    ),
            )
    }

    val searchPopEnter: NavEnterTransition = {
        fadeIn(
            animationSpec =
                tween(
                    durationMillis = MotionTokens.DurationMedium2,
                    easing = MotionTokens.EasingEmphasizedDecelerate,
                ),
        )
    }

    val searchPopExit: NavExitTransition = {
        fadeOut(
            animationSpec =
                tween(
                    durationMillis = MotionTokens.DurationMedium2,
                    easing = MotionTokens.EasingEmphasizedAccelerate,
                ),
        ) +
            scaleOut(
                targetScale = SEARCH_INITIAL_SCALE,
                animationSpec =
                    tween(
                        durationMillis = MotionTokens.DurationMedium2,
                        easing = MotionTokens.EasingEmphasizedAccelerate,
                    ),
            )
    }

    private const val SEARCH_INITIAL_SCALE: Float = 0.94f
}
