package com.lomo.ui.component.navigation

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing

internal object SidebarDrawerTokens {
    val TagRowShape = RoundedCornerShape(14.dp)
    val HubCardShape = RoundedCornerShape(32.dp)
    val NavigationItemShape = AppShapes.Full
    val IndicatorShape = AppShapes.Full

    val RowHeight = 48.dp
    val NavigationIconSize = 20.dp
    val TagLeadingIconSize = 20.dp
    val TagExpandButtonSize = 36.dp
    val TagExpandIconSize = 18.dp
    val TagRowStartPadding = AppSpacing.Medium
    val TagRowEndPadding = AppSpacing.Small
    val TagLabelSpacing = AppSpacing.MediumSmall

    const val TagDragScale = 1.02f
    const val TagDragAlpha = 0.92f

    private const val HubCardContainerAlpha = 0.25f
    private const val StatCardLabelAlpha = 0.7f
    private const val HubTitleAlpha = 0.8f
    private const val SelectedContainerAlpha = 0.5f
    private const val DividerAlpha = 0.2f

    fun cardContainerColor(colorScheme: ColorScheme): Color =
        colorScheme.surfaceVariant.copy(alpha = HubCardContainerAlpha)

    fun statCardLabelColor(colorScheme: ColorScheme): Color =
        colorScheme.onSurfaceVariant.copy(alpha = StatCardLabelAlpha)

    fun hubTitleColor(colorScheme: ColorScheme): Color =
        colorScheme.onSurfaceVariant.copy(alpha = HubTitleAlpha)

    fun selectedContainerColor(colorScheme: ColorScheme): Color =
        colorScheme.primaryContainer.copy(alpha = SelectedContainerAlpha)

    fun statRowContainerColor(colorScheme: ColorScheme): Color =
        colorScheme.primaryContainer.copy(alpha = 0.08f)

    fun trashSelectedContainerColor(colorScheme: ColorScheme): Color =
        colorScheme.errorContainer.copy(alpha = 0.7f)

    fun trashSelectedContentColor(colorScheme: ColorScheme): Color =
        colorScheme.onErrorContainer

    fun trashUnselectedContainerColor(colorScheme: ColorScheme): Color =
        colorScheme.errorContainer.copy(alpha = HubCardContainerAlpha)

    fun trashUnselectedContentColor(colorScheme: ColorScheme): Color =
        colorScheme.error

    fun heatmapYearBackgroundColor(colorScheme: ColorScheme): Color =
        cardContainerColor(colorScheme).compositeOver(colorScheme.surfaceContainerLow)

    fun dividerColor(colorScheme: ColorScheme): Color =
        colorScheme.outlineVariant.copy(alpha = DividerAlpha)

    object Footer {
        val CardShape = AppShapes.Large
        val ButtonShape = AppShapes.Medium

        fun cardContainerColor(colorScheme: ColorScheme): Color =
            colorScheme.surfaceContainerLow

        fun cardBorderColor(colorScheme: ColorScheme): Color =
            colorScheme.outlineVariant.copy(alpha = 0.2f)
    }
}
