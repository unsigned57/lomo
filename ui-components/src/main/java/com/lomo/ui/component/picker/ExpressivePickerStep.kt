package com.lomo.ui.component.picker

import androidx.compose.runtime.Composable

/**
 * One step in an [ExpressiveSteppedPickerDialog].
 *
 * [confirmEnabled] gates the footer button while this step is showing (e.g. a date step that has no
 * selection yet); [content] renders the step body, which should be a single picker surface so each
 * step fits on screen.
 */
data class ExpressivePickerStep(
    val confirmEnabled: Boolean = true,
    val content: @Composable () -> Unit,
)
