package com.lomo.ui.component.picker

/**
 * Pure paging decisions for [ExpressiveSteppedPickerDialog].
 *
 * Keeping the advance/confirm boundary here (instead of inline in the composable) makes the
 * "advance until the last step, then confirm" contract unit-testable without Compose, and gives the
 * reminder and backfill dialogs one shared definition of "which step is terminal".
 */
internal object SteppedPickerPaging {
    /** True when [currentIndex] is the terminal step, so the footer should confirm rather than advance. */
    fun isLastStep(
        currentIndex: Int,
        stepCount: Int,
    ): Boolean = currentIndex >= stepCount - 1

    /** Index to advance to, or `null` when [currentIndex] is already the last step (caller should confirm). */
    fun nextStepIndex(
        currentIndex: Int,
        stepCount: Int,
    ): Int? = if (isLastStep(currentIndex, stepCount)) null else currentIndex + 1
}
