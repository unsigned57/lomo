package com.lomo.ui.component.input

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing

internal object InputSheetTokens {
    val CompactFallbackHeight = 228.dp
    val CollapsedMinHeight = 120.dp
    val ExpandedMinHeight = 300.dp
    val CollapsedMaxHeight = 240.dp
    val ExpandedMaxHeight = 600.dp
    val CompactCornerRadius = 28.dp
    val ExpandedCornerRadius = 0.dp
    val CollapsedInset = 0.dp
    val PanelPadding = AppSpacing.Medium
    val ToolbarTopPadding = AppSpacing.MediumSmall
    val SegmentedControlShape = AppShapes.Large
    val SegmentedControlContentPadding = AppSpacing.ExtraSmall
    val ModePillShape = AppShapes.Large
    val ModePillContentPadding = PaddingValues(horizontal = 12.dp, vertical = AppSpacing.Small)
    val PreviewShape = AppShapes.Large
    val PreviewHorizontalPadding = 16.dp
    val EditorContainerShape = AppShapes.Large
    val EditorContainerPaddingHorizontal = 16.dp
    val EditorContainerPaddingVertical = 12.dp
    val ActionBadgeShape = AppShapes.Large
    val ActionBadgeContentPadding = PaddingValues(horizontal = 12.dp, vertical = AppSpacing.Small)
    val ActionBadgeIconSize = 18.dp
    val ToolbarSubmitIconSize = 18.dp
    val TagSelectorHeight = 32.dp
    val RecordingTrackHeight = 48.dp
    val RecordingWaveformHeight = AppSpacing.ExtraSmall
    val RecordingCancelButtonSize = 56.dp
    val RecordingCancelIconSize = 32.dp
    val RecordingStopButtonSize = 72.dp
    val RecordingStopIconSize = 36.dp

    const val ToolbarDragScaleFactor = 1.05f
    const val ToolbarDragAlpha = 0.92f

    private const val PlaceholderAlpha = 0.5f

    fun placeholderColor(colorScheme: ColorScheme): Color =
        colorScheme.onSurfaceVariant.copy(alpha = PlaceholderAlpha)
}
