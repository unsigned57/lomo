package com.lomo.ui.component.card

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.MotionTokens

internal object MemoCardTokens {
    val ContainerShape = AppShapes.Medium
    val ContainerPadding = AppSpacing.CardPadding
    val HeaderActionSpacing = AppSpacing.Small
    val PinnedBadgeShape = AppShapes.Small
    val PinnedBadgeHorizontalPadding = AppSpacing.Small
    val PinnedBadgeVerticalPadding = AppSpacing.ExtraSmall
    val PinnedBadgeSpacing = AppSpacing.ExtraSmall
    val PinnedIconSize = 12.dp
    val MenuButtonSize = 20.dp
    val MenuIconSize = 12.dp
    val BodyVerticalPadding = AppSpacing.ExtraSmall
    val FooterTopPadding = AppSpacing.ExtraSmall
    val FooterItemSpacing = AppSpacing.ExtraSmall
    val ExpandButtonInteractiveSize = 24.dp
    val ExpandButtonContentPadding = PaddingValues(0.dp)
    val CollapsedBodyMaxHeight = 240.dp
    val CollapsedBodyOverlayHeight = 48.dp

    const val ExpandAnimationDurationMillis = MotionTokens.DurationMedium1
    const val PressFeedbackMillis = 120L
}
