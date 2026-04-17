package com.lomo.ui.component.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import kotlin.math.roundToInt

private const val PERCENT_SCALE = 100

@Composable
fun PeriodReportCard(
    title: String,
    count: Int,
    previousCount: Int,
    periodDays: Int,
    modifier: Modifier = Modifier,
) {
    val dailyAvg = if (periodDays > 0) count.toDouble() / periodDays else 0.0
    val trendPercent =
        if (previousCount > 0) {
            ((count - previousCount).toDouble() / previousCount * PERCENT_SCALE).roundToInt()
        } else {
            null
        }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AppShapes.Medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Medium),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "$count memos",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "%.1f / day".format(dailyAvg),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (trendPercent != null) {
                val trendText = if (trendPercent >= 0) "+$trendPercent%" else "$trendPercent%"
                val trendColor = if (trendPercent >= 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = trendText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = trendColor,
                )
            }
        }
    }
}
