package com.lomo.ui.component.card

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing

internal object MemoCardTokens {
    val ContainerShape = AppShapes.LargeIncreased
    val ContainerPadding = 20.dp
    val HeaderActionSpacing = AppSpacing.Small
    val PinnedBadgeShape = AppShapes.Small
    val PinnedBadgeHorizontalPadding = AppSpacing.Small
    val PinnedBadgeVerticalPadding = AppSpacing.ExtraSmall
    val PinnedBadgeSpacing = AppSpacing.ExtraSmall
    val PinnedIconSize = 12.dp
    val MenuButtonSize = 48.dp
    val MenuIconSize = 20.dp
    val BodyVerticalPadding = AppSpacing.ExtraSmall
    val FooterTopPadding = AppSpacing.ExtraSmall
    val FooterItemSpacing = AppSpacing.ExtraSmall
    val ExpandButtonInteractiveSize = 48.dp
    val ExpandButtonContentPadding = PaddingValues(0.dp)
    val CollapsedBodyMaxHeight = 240.dp
    val CollapsedBodyOverlayHeight = 48.dp

    const val PressFeedbackMillis = 120L
}
