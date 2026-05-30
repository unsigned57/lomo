package com.lomo.app.feature.memo

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class ReminderIntervalChoiceLayoutSpec(
    val choiceWidth: Dp,
    val choiceHeight: Dp,
    val horizontalSpacing: Dp,
    val verticalSpacing: Dp,
)

internal object ReminderIntervalChoiceLayoutPolicy {
    fun spec(): ReminderIntervalChoiceLayoutSpec =
        ReminderIntervalChoiceLayoutSpec(
            choiceWidth = 72.dp,
            choiceHeight = 40.dp,
            horizontalSpacing = 8.dp,
            verticalSpacing = 8.dp,
        )
}
