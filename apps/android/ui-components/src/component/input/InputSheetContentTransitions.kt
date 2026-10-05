package com.lomo.ui.component.input

import androidx.compose.material3.MotionScheme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith

internal fun fadeScaleContentTransition(motionScheme: MotionScheme): ContentTransform =
    (
        fadeIn(
            animationSpec =
                motionScheme.defaultEffectsSpec(),
        ) +
            scaleIn(
                initialScale = 0.95f,
                animationSpec =
                    motionScheme.defaultSpatialSpec(),
            )
    ).togetherWith(
        fadeOut(
            animationSpec =
                motionScheme.fastEffectsSpec(),
        ),
    )
