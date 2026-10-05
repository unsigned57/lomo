package com.lomo.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.MotionScheme

private const val PAGE_OFFSET_FRACTION = 0.08f

/** Direction is +1 toward the layout's end edge and -1 toward its start edge. */
fun pageEnterTransition(scheme: MotionScheme, direction: Int): EnterTransition =
    slideInHorizontally(
        initialOffsetX = { (it * PAGE_OFFSET_FRACTION * direction).toInt() },
        animationSpec = scheme.defaultSpatialSpec(),
    ) + fadeIn(scheme.defaultEffectsSpec())

fun pageExitTransition(scheme: MotionScheme, direction: Int): ExitTransition =
    slideOutHorizontally(
        targetOffsetX = { (it * PAGE_OFFSET_FRACTION * direction).toInt() },
        animationSpec = scheme.defaultSpatialSpec(),
    ) + fadeOut(scheme.fastEffectsSpec())
