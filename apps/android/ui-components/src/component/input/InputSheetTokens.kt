package com.lomo.ui.component.input

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing

internal object InputSheetTokens {
    /** Matches the app's modal bottom sheets, so the editor reads as the same surface family. */
    val MaxWidth = 640.dp
    val CornerRadius = 28.dp

    /** Clearance above a card-form sheet on windows wider than [MaxWidth]. */
    val CardTopMargin = 24.dp
    const val ScrimAlpha = 0.32f
    val BackPreviewTravel = 24.dp

    /** A release faster than this follows the fling direction instead of the nearer form. */
    val SettleVelocityThreshold = 125.dp
    val EditorCanvasShape = AppShapes.LargeIncreased
    val PanelHorizontalPadding = AppSpacing.Medium
    val PanelBottomPadding = AppSpacing.Medium
    val HeaderCanvasGap = AppSpacing.Small

    /** Space above the shared header row: short on the compact card, the long form's top padding. */
    val HeaderTopInsetCompact = AppSpacing.Small
    val HeaderTopInsetExpanded = AppSpacing.Medium

    /** Clearance on each side of the handle, between the header's start and end slots. */
    val DragHandleSideGap = AppSpacing.Medium
    val HeaderDividerHeight = 20.dp
    val ToolbarTopPadding = AppSpacing.MediumSmall

    /** The formatting toolbar sinks this far while it fades out for preview. */
    val ToolbarPreviewOffset = 12.dp
    val SegmentedControlShape = AppShapes.Full
    val SegmentedControlContentPadding = PaddingValues(horizontal = 4.dp, vertical = 3.dp)
    val ModePillShape = AppShapes.Full
    val ModePillContentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
    val HeaderIconButtonWidth = 36.dp
    val HeaderIconButtonHeight = 32.dp
    val HeaderIconSize = 18.dp
    val DragHandleWidth = 36.dp
    val DragHandleHeight = 4.dp
    val PreviewShape = AppShapes.LargeIncreased
    val PreviewHorizontalPadding = 16.dp
    val EditorContainerPaddingHorizontal = 16.dp
    val EditorContainerPaddingVertical = 12.dp

    /** One header row: tall enough for the mode switch, a metadata badge and the icon buttons. */
    val HeaderActionClusterHeight = 36.dp
    val ActionBadgeShape = AppShapes.Full
    val ActionBadgeContentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    val ActionBadgeIconSize = 16.dp
    val ToolbarSubmitIconSize = 22.dp
    val TagSelectorHeight = 32.dp
    val RecordingTrackHeight = 48.dp
    val RecordingWaveformHeight = AppSpacing.ExtraSmall
    val RecordingCancelButtonSize = 56.dp
    val RecordingCancelIconSize = 32.dp
    val RecordingStopButtonSize = 72.dp
    val RecordingStopIconSize = 36.dp

    const val ToolbarDragScaleFactor = 1.05f
    const val ToolbarDragAlpha = 0.92f

    val ToolbarIslandShape = AppShapes.Full
    val ToolbarIslandContentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
    val SecondaryTrayPillShape = AppShapes.Large
    val SecondaryTrayPillContentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    val SecondaryTrayIconSize = 18.dp

    private const val DisabledToolLabelAlpha = 0.45f
    private const val DisabledToolIconAlpha = 0.38f

    fun disabledToolLabelColor(colorScheme: ColorScheme): Color =
        colorScheme.onSurfaceVariant.copy(alpha = DisabledToolLabelAlpha)

    fun disabledToolIconColor(colorScheme: ColorScheme): Color =
        colorScheme.onSurfaceVariant.copy(alpha = DisabledToolIconAlpha)

    private const val PlaceholderAlpha = 0.5f

    fun placeholderColor(colorScheme: ColorScheme): Color = colorScheme.onSurfaceVariant.copy(alpha = PlaceholderAlpha)
}
