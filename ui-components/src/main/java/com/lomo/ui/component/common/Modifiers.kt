package com.lomo.ui.component.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput

/**
 * A modifier that scales the element down when pressed, using a spring animation. This provides a
 * tactile, playful "squishy" feel typically found in iOS or premium Android apps.
 */
fun Modifier.scaleClickable(
    pressedScale: Float = 0.95f,
    onClick: () -> Unit,
): Modifier =
    composed {
        var isPressed by remember { mutableStateOf(false) }
        val scale by
            animateFloatAsState(
                targetValue = if (isPressed) pressedScale else 1f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 800f), // Bouncy spring
                label = "scale",
            )

        this
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitFirstDown(requireUnconsumed = false)
                        isPressed = true
                        waitForUpOrCancellation()
                        isPressed = false
                    }
                }
            }.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication =
                null, // We handle the visual feedback via scale, or let standard ripple
                // work if needed (but usually scale replaces ripple or works with
                // it)
                onClick = onClick,
            )
    }

/**
 * Version that works with existing clickables (just adds the scale effect state), useful if you
 * want to keep the ripple but just add the motion.
 */
fun Modifier.scaleOnPress(pressedScale: Float = 0.90f): Modifier =
    composed {
        var isPressed by remember { mutableStateOf(false) }
        val scale by
            animateFloatAsState(
                targetValue = if (isPressed) pressedScale else 1f,
                animationSpec = spring(dampingRatio = 0.5f, stiffness = 600f),
                label = "scale",
            )

        this
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitFirstDown(requireUnconsumed = false)
                        isPressed = true
                        waitForUpOrCancellation()
                        isPressed = false
                    }
                }
            }
    }
