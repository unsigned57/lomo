package com.lomo.ui.component.card

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

internal data class MemoMetadataPillColors(
    val containerColor: Color,
    val contentColor: Color,
)

internal fun memoTagPillColors(colorScheme: ColorScheme): MemoMetadataPillColors =
    MemoMetadataPillColors(
        containerColor = colorScheme.secondaryContainer,
        contentColor = colorScheme.onSecondaryContainer,
    )

internal fun memoReminderPillColors(
    isExhausted: Boolean,
    colorScheme: ColorScheme,
): MemoMetadataPillColors =
    if (isExhausted) {
        MemoMetadataPillColors(
            containerColor = colorScheme.surfaceContainerHigh,
            contentColor = colorScheme.onSurfaceVariant,
        )
    } else {
        MemoMetadataPillColors(
            containerColor = colorScheme.primaryContainer,
            contentColor = colorScheme.onPrimaryContainer,
        )
    }
