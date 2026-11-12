package com.lomo.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.MotionScheme
import androidx.compose.ui.unit.dp

/**
 * Short shared-axis travel keeps navigation directional without sweeping an entire page
 * across the viewport. The same distance is used by settings' seekable back preview.
 */
val PageTransitionDistance = 48.dp

/** Direction is +1 toward the layout's end edge and -1 toward its start edge. */
fun pageEnterTransition(scheme: MotionScheme, direction: Int, offsetPx: Float): EnterTransition = slideInHorizontally(
    initialOffsetX = { (offsetPx * direction).toInt() },
    animationSpec = scheme.defaultSpatialSpec(),
) + fadeIn(scheme.defaultEffectsSpec())

fun pageExitTransition(scheme: MotionScheme, direction: Int, offsetPx: Float): ExitTransition = slideOutHorizontally(
    targetOffsetX = { (offsetPx * direction).toInt() },
    animationSpec = scheme.defaultSpatialSpec(),
) + fadeOut(scheme.defaultEffectsSpec())
