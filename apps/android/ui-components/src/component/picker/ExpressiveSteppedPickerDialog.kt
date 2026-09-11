package com.lomo.ui.component.picker

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.collections.immutable.ImmutableList

/**
 * Multi-step variant of [ExpressivePickerDialog]: shows exactly one [ExpressivePickerStep] at a time
 * with a horizontal slide between steps. Composite date/time/option flows (reminder, backfill) use this
 * instead of stacking multiple full-height pickers into one over-tall dialog whose footer button would
 * scroll off the bottom of the screen.
 *
 * The footer button reads [advanceLabel] on every step except the last, where it reads [confirmLabel]
 * and invokes [onConfirm]. The advance/confirm boundary is owned by [SteppedPickerPaging].
 */
@Composable
fun ExpressiveSteppedPickerDialog(
    title: String,
    steps: ImmutableList<ExpressivePickerStep>,
    advanceLabel: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String? = null,
) {
    var stepIndex by remember { mutableIntStateOf(0) }
    val isLastStep = SteppedPickerPaging.isLastStep(currentIndex = stepIndex, stepCount = steps.size)

    ExpressivePickerDialog(
        title = title,
        confirmLabel = if (isLastStep) confirmLabel else advanceLabel,
        confirmEnabled = steps[stepIndex].confirmEnabled,
        dismissLabel = dismissLabel,
        onConfirm = {
            when (val next = SteppedPickerPaging.nextStepIndex(currentIndex = stepIndex, stepCount = steps.size)) {
                null -> onConfirm()
                else -> stepIndex = next
            }
        },
        onDismiss = onDismiss,
    ) {
        AnimatedContent(
            targetState = stepIndex,
            transitionSpec = {
                val forward = targetState > initialState
                val direction = if (forward) 1 else -1
                (
                    slideInHorizontally { width -> direction * width / STEP_SLIDE_FRACTION } + fadeIn()
                ) togetherWith (
                    slideOutHorizontally { width -> -direction * width / STEP_SLIDE_FRACTION } + fadeOut()
                ) using SizeTransform(clip = false)
            },
            label = "ExpressiveSteppedPickerStep",
        ) { index ->
            steps[index].content()
        }
    }
}

private const val STEP_SLIDE_FRACTION = 6
