package com.lomo.app.widget

import android.content.Context
import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.lomo.app.R
import com.lomo.domain.usecase.DefaultDispatcherProvider
import kotlinx.serialization.SerializationException
import timber.log.Timber
import java.io.IOException
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import androidx.glance.color.ColorProvider as dayNightColorProvider

internal val WidgetOuterCornerRadius = 24.dp
internal val WidgetCardCornerRadius = 16.dp
internal val WidgetChipCornerRadius = 10.dp

internal val WidgetLayeredCardSurface: ColorProvider =
    dayNightColorProvider(
        day = Color(0xCCFFFFFF),
        night = Color(0x88242832),
    )

internal val WidgetMutedChipSurface: ColorProvider =
    dayNightColorProvider(
        day = Color(0x161E2433),
        night = Color(0x28E8ECF4),
    )

internal data class WidgetRenderSnapshot(
    val availability: WidgetSnapshotAvailability,
    val items: List<WidgetGlanceSnapshotItem> = emptyList(),
    val tasks: List<WidgetGlanceTaskItem> = emptyList(),
    val stats: WidgetGlanceStats? = null,
    val reviews: List<WidgetGlanceReviewItem> = emptyList(),
)

internal suspend fun readWidgetRenderSnapshot(context: Context): WidgetRenderSnapshot {
    val snapshot =
        try {
            WidgetGlanceSnapshotStore(
                file = context.filesDir.resolve(WIDGET_GLANCE_SNAPSHOT_FILE_NAME),
                ioDispatcher = DefaultDispatcherProvider().io,
            ).read()
        } catch (failure: SerializationException) {
            Timber.w(failure, "Widget snapshot unreadable; rendering unavailable state")
            WidgetGlanceSnapshot.Absent
        } catch (failure: IllegalArgumentException) {
            Timber.w(failure, "Widget snapshot schema mismatch; rendering unavailable state")
            WidgetGlanceSnapshot.Absent
        } catch (failure: IOException) {
            Timber.w(failure, "Widget snapshot I/O error; rendering unavailable state")
            WidgetGlanceSnapshot.Absent
        }
    return when (snapshot) {
        WidgetGlanceSnapshot.Absent ->
            WidgetRenderSnapshot(availability = WidgetSnapshotAvailability.UNAVAILABLE)

        is WidgetGlanceSnapshot.Ready ->
            WidgetRenderSnapshot(
                availability = snapshot.availability,
                items = snapshot.items,
                tasks = snapshot.tasks,
                stats = snapshot.stats,
                reviews = snapshot.reviews,
            )
    }
}

internal fun widgetLaunchAction(context: Context, target: WidgetLaunchTarget): Action =
    actionStartActivity(WidgetLaunchActivity.createIntent(context, target))

@Composable
internal fun WidgetShell(
    backgroundClick: Action? = null,
    contentPadding: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val baseModifier =
        GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(WidgetOuterCornerRadius)
            .padding(contentPadding)
    val modifier = if (backgroundClick != null) baseModifier.clickable(backgroundClick) else baseModifier
    Column(
        modifier = modifier,
        content = content,
    )
}

@Composable
internal fun WidgetHeaderRow(
    context: Context,
    @DrawableRes iconRes: Int,
    @StringRes titleRes: Int,
    titleClick: Action,
    badgeText: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = GlanceModifier.clickable(titleClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                GlanceModifier
                    .size(26.dp)
                    .background(GlanceTheme.colors.primaryContainer)
                    .cornerRadius(9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(iconRes),
                    contentDescription = null,
                    modifier = GlanceModifier.size(15.dp),
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
                )
            }
            Spacer(GlanceModifier.width(8.dp))
            Text(
                text = context.getString(titleRes),
                maxLines = 1,
                style =
                TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            if (!badgeText.isNullOrBlank()) {
                Spacer(GlanceModifier.width(6.dp))
                WidgetPillLabel(
                    text = badgeText,
                    background = GlanceTheme.colors.secondaryContainer,
                    textColor = GlanceTheme.colors.onSecondaryContainer,
                )
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        trailing()
    }
}

@Composable
internal fun WidgetActionIconButton(
    @DrawableRes iconRes: Int,
    contentDescription: String,
    onClick: Action,
    containerColor: ColorProvider = GlanceTheme.colors.secondaryContainer,
    iconTint: ColorProvider = GlanceTheme.colors.onSecondaryContainer,
    sizeDp: Dp = 32.dp,
    iconSizeDp: Dp = 17.dp,
) {
    Box(
        modifier =
        GlanceModifier
            .size(sizeDp)
            .background(containerColor)
            .cornerRadius(16.dp)
            .clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = contentDescription,
            modifier = GlanceModifier.size(iconSizeDp),
            colorFilter = ColorFilter.tint(iconTint),
        )
    }
}

@Composable
internal fun WidgetPillLabel(
    text: String,
    background: ColorProvider = WidgetMutedChipSurface,
    textColor: ColorProvider = GlanceTheme.colors.onSurfaceVariant,
    onClick: Action? = null,
) {
    val baseModifier =
        GlanceModifier
            .background(background)
            .cornerRadius(WidgetChipCornerRadius)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    val modifier = if (onClick != null) baseModifier.clickable(onClick) else baseModifier
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            maxLines = 1,
            style =
            TextStyle(
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Composable
internal fun WidgetNoticeCard(
    context: Context,
    @DrawableRes iconRes: Int,
    message: String,
    iconTint: ColorProvider = GlanceTheme.colors.primary,
    onClick: Action = widgetLaunchAction(context, WidgetLaunchTarget.DefaultMain),
) {
    Box(
        modifier =
        GlanceModifier
            .fillMaxSize()
            .background(WidgetLayeredCardSurface)
            .cornerRadius(WidgetCardCornerRadius)
            .clickable(onClick)
            .padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier =
                GlanceModifier
                    .size(34.dp)
                    .background(GlanceTheme.colors.primaryContainer)
                    .cornerRadius(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(iconRes),
                    contentDescription = null,
                    modifier = GlanceModifier.size(18.dp),
                    colorFilter = ColorFilter.tint(iconTint),
                )
            }
            Spacer(GlanceModifier.height(8.dp))
            Text(
                text = message,
                maxLines = 3,
                style =
                TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }
}

internal fun formatWidgetRelativeTime(timestampMillis: Long, nowMillis: Long): String =
    DateUtils.getRelativeTimeSpanString(
        timestampMillis,
        nowMillis,
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()

internal fun formatWidgetDateAgeLabel(context: Context, dateKey: String, today: LocalDate): String {
    val parsed = parseWidgetIsoDateOrNull(dateKey) ?: return dateKey
    val days = ChronoUnit.DAYS.between(parsed, today).coerceAtLeast(0L)
    return when (days) {
        0L -> context.getString(R.string.widget_review_age_today)
        1L -> context.getString(R.string.widget_review_age_yesterday)
        else -> context.getString(R.string.widget_review_age_days_ago, days.toInt())
    }
}

private fun parseWidgetIsoDateOrNull(raw: String): LocalDate? {
    if (raw.length < DATE_KEY_PREFIX_LENGTH) return null
    val slice = raw.substring(0, DATE_KEY_PREFIX_LENGTH)
    if (slice[ISO_FIRST_DASH_INDEX] != '-' || slice[ISO_SECOND_DASH_INDEX] != '-') return null
    val year = slice.substring(0, ISO_YEAR_END).toIntOrNull() ?: return null
    val month = slice.substring(ISO_MONTH_START, ISO_MONTH_END).toIntOrNull() ?: return null
    val day = slice.substring(ISO_DAY_START, ISO_DAY_END).toIntOrNull() ?: return null
    if (year !in MIN_VALID_YEAR..MAX_VALID_YEAR || month !in 1..MONTHS_IN_YEAR) return null
    val maxDay = java.time.Month.of(month).length(java.time.Year.isLeap(year.toLong()))
    if (day !in 1..maxDay) return null
    return LocalDate.of(year, month, day)
}

private const val DATE_KEY_PREFIX_LENGTH = 10
private const val ISO_YEAR_END = 4
private const val ISO_FIRST_DASH_INDEX = 4
private const val ISO_MONTH_START = 5
private const val ISO_MONTH_END = 7
private const val ISO_SECOND_DASH_INDEX = 7
private const val ISO_DAY_START = 8
private const val ISO_DAY_END = 10
private const val MIN_VALID_YEAR = 1900
private const val MAX_VALID_YEAR = 2100
private const val MONTHS_IN_YEAR = 12

private const val FNV1A_OFFSET_BASIS = -3750763034362895579L // 0xcbf29ce484222325
private const val FNV1A_PRIME = 1099511628211L

/**
 * Computes a deterministic non-negative 63-bit item ID outside Glance's reserved negative item ID
 * range so `LazyColumn` keeps stable view identities across snapshot updates and checkbox toggles.
 */
internal fun stableWidgetListItemId(key: String): Long {
    var hash = FNV1A_OFFSET_BASIS
    for (index in key.indices) {
        hash = hash xor key[index].code.toLong()
        hash *= FNV1A_PRIME
    }
    return hash and Long.MAX_VALUE
}
