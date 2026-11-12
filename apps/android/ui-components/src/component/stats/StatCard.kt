package com.lomo.ui.component.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing

/**
 * M3 Expressive statistic tile: a tonal container with the emphasized value and optional trailing
 * glyph badge on the top row, and the supporting label below.
 */
@Composable
fun StatCard(value: String, label: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Surface(
        modifier = modifier,
        shape = AppShapes.LargeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(AppSpacing.MediumSmall)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                com.lomo.ui.component.common.LomoStateContent(
                    state = value,
                    contentKey = { it },
                    modifier = Modifier.weight(1f, fill = false),
                ) { displayedValue ->
                    Text(
                        text = displayedValue,
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (icon != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = AppShapes.Small,
                        modifier = Modifier.size(StatCardTokens.BadgeSize),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(StatCardTokens.IconSize),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(AppSpacing.ExtraSmall))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private object StatCardTokens {
    val BadgeSize = 26.dp
    val IconSize = 15.dp
}
