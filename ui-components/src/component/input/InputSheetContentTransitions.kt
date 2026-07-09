package com.lomo.ui.component.input

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import com.lomo.ui.theme.MotionTokens

internal fun fadeScaleContentTransition(): ContentTransform =
    (
        fadeIn(
            animationSpec =
                androidx.compose.animation.core
                    .tween(MotionTokens.DurationMedium2),
        ) +
            scaleIn(
                initialScale = 0.95f,
                animationSpec =
                    androidx.compose.animation.core.tween(
                        MotionTokens.DurationMedium2,
                        easing = MotionTokens.EasingEmphasizedDecelerate,
                    ),
            )
    ).togetherWith(
        fadeOut(
            animationSpec =
                androidx.compose.animation.core
                    .tween(durationMillis = MotionTokens.DurationShort4),
        ),
    )
