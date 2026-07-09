package com.lomo.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn

object MotionTokens {
    const val DurationShort4 = 200
    const val DurationMedium1 = 250
    const val DurationMedium2 = 300
    const val DurationLong2 = 500
    private const val STANDARD_X1 = 0.4f
    private const val STANDARD_Y1 = 0.0f
    private const val STANDARD_X2 = 0.2f
    private const val STANDARD_Y2 = 1.0f
    private const val EMPHASIZED_X1 = 0.2f
    private const val EMPHASIZED_Y1 = 0.0f
    private const val EMPHASIZED_X2 = 0.0f
    private const val EMPHASIZED_Y2 = 1.0f
    private const val EMPHASIZED_ACCELERATE_X1 = 0.3f
    private const val EMPHASIZED_ACCELERATE_Y1 = 0.0f
    private const val EMPHASIZED_ACCELERATE_X2 = 0.8f
    private const val EMPHASIZED_ACCELERATE_Y2 = 0.15f
    private const val EMPHASIZED_DECELERATE_X1 = 0.05f
    private const val EMPHASIZED_DECELERATE_Y1 = 0.7f
    private const val EMPHASIZED_DECELERATE_X2 = 0.1f
    private const val EMPHASIZED_DECELERATE_Y2 = 1.0f
    private const val ENTER_CONTENT_INITIAL_SCALE = 0.92f

    val EasingStandard =
        androidx.compose.animation.core
            .CubicBezierEasing(STANDARD_X1, STANDARD_Y1, STANDARD_X2, STANDARD_Y2)
    val EasingEmphasized =
        androidx.compose.animation.core
            .CubicBezierEasing(EMPHASIZED_X1, EMPHASIZED_Y1, EMPHASIZED_X2, EMPHASIZED_Y2)
    val EasingEmphasizedAccelerate =
        androidx.compose.animation.core
            .CubicBezierEasing(
                EMPHASIZED_ACCELERATE_X1,
                EMPHASIZED_ACCELERATE_Y1,
                EMPHASIZED_ACCELERATE_X2,
                EMPHASIZED_ACCELERATE_Y2,
            )
    val EasingEmphasizedDecelerate =
        androidx.compose.animation.core
            .CubicBezierEasing(
                EMPHASIZED_DECELERATE_X1,
                EMPHASIZED_DECELERATE_Y1,
                EMPHASIZED_DECELERATE_X2,
                EMPHASIZED_DECELERATE_Y2,
            )

    val enterContent =
        fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
            scaleIn(
                initialScale = ENTER_CONTENT_INITIAL_SCALE,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            )

    val exitContent = fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
}
