package com.lomo.ui.component.media

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing

internal object AudioPlayerCardTokens {
    val ContainerHeight = 56.dp
    val ContainerShape = AppShapes.Medium
    val ContentHorizontalPadding = AppSpacing.MediumSmall
    val ControlButtonSize = 48.dp
    val ControlContainerSize = 32.dp
    val ControlContainerShape = AppShapes.Large
    val ControlIconSize = 20.dp
    val ContentSpacing = AppSpacing.MediumSmall
    val ProgressHeight = 18.dp
    val ProgressWavelength = 28.dp
    val ProgressWaveSpeed = 18.dp
    val TimestampMinWidth = 84.dp

    private const val ContainerAlpha = 0.4f
    private const val ActiveTrackAlpha = 0.22f
    private const val InactiveTrackAlpha = 0.28f

    fun containerColor(colorScheme: ColorScheme): Color =
        colorScheme.secondaryContainer.copy(alpha = ContainerAlpha)

    fun progressTrackColor(
        colorScheme: ColorScheme,
        active: Boolean,
    ): Color =
        if (active) {
            colorScheme.onSecondaryContainer.copy(alpha = ActiveTrackAlpha)
        } else {
            colorScheme.outlineVariant.copy(alpha = InactiveTrackAlpha)
        }
}
