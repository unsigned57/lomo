package com.lomo.ui.component.input

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.MotionTokens

@Composable
internal fun InputEditorActionBadgeContent(
    badge: InputEditorActionBadge?,
    onClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = badge != null,
        enter =
            expandVertically(
                animationSpec =
                    androidx.compose.animation.core.tween(
                        durationMillis = MotionTokens.DurationMedium2,
                        easing = MotionTokens.EasingEmphasized,
                    ),
            ) + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        val actionBadge = badge ?: return@AnimatedVisibility
        Surface(
            shape = InputSheetTokens.ActionBadgeShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier =
                Modifier
                    .padding(
                        top = AppSpacing.ExtraSmall,
                        bottom = AppSpacing.MediumSmall,
                    )
                    .clip(InputSheetTokens.ActionBadgeShape)
                    .clickable(onClick = onClick),
        ) {
            Row(
                modifier = Modifier.padding(InputSheetTokens.ActionBadgeContentPadding),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = actionBadge.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(InputSheetTokens.ActionBadgeIconSize),
                )
                Text(
                    text = actionBadge.text,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}
