package com.lomo.ui.component.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.font.FontWeight
import com.lomo.ui.generated.resources.Res
import com.lomo.ui.generated.resources.period_report_daily_average
import com.lomo.ui.generated.resources.period_report_memo_count
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

private const val PERCENT_SCALE = 100

@Composable
fun PeriodReportCard(title: String, count: Int, previousCount: Int, periodDays: Int, modifier: Modifier = Modifier) {
    val dailyAvg = formatPeriodDailyAverage(count, periodDays, LocalLocale.current.platformLocale)
    val trendPercent =
        if (previousCount > 0) {
            ((count - previousCount).toDouble() / previousCount * PERCENT_SCALE).roundToInt()
        } else {
            null
        }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AppShapes.LargeIncreased,
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
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = pluralStringResource(Res.plurals.period_report_memo_count, count, count),
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.period_report_daily_average, dailyAvg),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (trendPercent != null) {
                val trendText = if (trendPercent >= 0) "+$trendPercent%" else "$trendPercent%"
                val badgeContainerColor =
                    if (trendPercent >= 0) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                val badgeContentColor =
                    if (trendPercent >= 0) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                Surface(
                    shape = AppShapes.Full,
                    color = badgeContainerColor,
                ) {
                    Text(
                        text = trendText,
                        style = MaterialTheme.typography.labelLargeEmphasized,
                        fontWeight = FontWeight.Bold,
                        color = badgeContentColor,
                        modifier =
                        Modifier.padding(
                            horizontal = AppSpacing.MediumSmall,
                            vertical = AppSpacing.ExtraSmall,
                        ),
                    )
                }
            }
        }
    }
}

internal fun formatPeriodDailyAverage(count: Int, periodDays: Int, locale: Locale): String {
    val average = if (periodDays > 0) count.toDouble() / periodDays else 0.0
    return NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(average)
}
