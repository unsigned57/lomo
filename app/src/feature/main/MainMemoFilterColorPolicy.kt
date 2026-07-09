package com.lomo.app.feature.main

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

internal data class MainMemoDateRangeIconColors(
    val containerColor: Color,
    val iconColor: Color,
)

internal data class MainMemoDateFieldColors(
    val containerColor: Color,
    val iconColor: Color,
    val clearActionColor: Color,
    val labelColor: Color,
    val valueColor: Color,
)

internal fun mainMemoDateRangeIconColors(
    isActive: Boolean,
    colorScheme: ColorScheme,
): MainMemoDateRangeIconColors =
    if (isActive) {
        MainMemoDateRangeIconColors(
            containerColor = colorScheme.primaryContainer,
            iconColor = colorScheme.onPrimaryContainer,
        )
    } else {
        MainMemoDateRangeIconColors(
            containerColor = colorScheme.surfaceContainerHigh,
            iconColor = colorScheme.onSurfaceVariant,
        )
    }

internal fun mainMemoDateFieldColors(
    hasValue: Boolean,
    colorScheme: ColorScheme,
): MainMemoDateFieldColors =
    if (hasValue) {
        MainMemoDateFieldColors(
            containerColor = colorScheme.primaryContainer,
            iconColor = colorScheme.onPrimaryContainer,
            clearActionColor = colorScheme.onPrimaryContainer,
            labelColor = colorScheme.onPrimaryContainer,
            valueColor = colorScheme.onPrimaryContainer,
        )
    } else {
        MainMemoDateFieldColors(
            containerColor = colorScheme.surfaceContainerLow,
            iconColor = colorScheme.onSurfaceVariant,
            clearActionColor = colorScheme.onSurfaceVariant,
            labelColor = colorScheme.onSurfaceVariant,
            valueColor = colorScheme.onSurface,
        )
    }
