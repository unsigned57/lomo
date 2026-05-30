package com.lomo.app.feature.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.app.feature.common.UiState
import com.lomo.domain.model.MemoStatistics
import com.lomo.ui.component.common.ExpressiveContainedLoadingIndicator
import com.lomo.ui.component.stats.CalendarHeatmap
import com.lomo.ui.component.stats.ChartTagSlice
import com.lomo.ui.component.stats.HourlyActivityChart
import com.lomo.ui.component.stats.PeriodReportCard
import com.lomo.ui.component.stats.StatCard
import com.lomo.ui.component.stats.TagDistributionChart
import com.lomo.ui.component.stats.WeeklyHourHeatmap
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatisticsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val shareImageEvent by viewModel.shareImageEvent.collectAsStateWithLifecycle()
    val shareErrorMessage by viewModel.shareErrorMessage.collectAsStateWithLifecycle()
    val haptic = LocalAppHapticFeedback.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    LaunchedEffect(viewModel) {
        viewModel.ensureLoaded()
    }

    StatisticsShareEffects(
        context = context,
        shareImageEvent = shareImageEvent,
        shareErrorMessage = shareErrorMessage,
        snackbarHostState = snackbarHostState,
        onShareEventConsumed = viewModel::consumeShareImageEvent,
        onShareFailed = viewModel::reportShareFailure,
        onShareErrorConsumed = viewModel::clearShareError,
    )

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sidebar_statistics)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            haptic.medium()
                            onBackClick()
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when (val state = uiState) {
                is UiState.Loading -> ExpressiveContainedLoadingIndicator()
                is UiState.Error -> {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(AppSpacing.Medium),
                    )
                }
                is UiState.Success -> {
                    StatisticsContent(
                        stats = state.data,
                        onShareImageCaptured = viewModel::shareStatisticsImage,
                        onShareCaptureFailed = viewModel::reportShareFailure,
                    )
                }
                else -> Unit
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatisticsContent(
    stats: MemoStatistics,
    onShareImageCaptured: (StatisticsPngSource) -> Unit,
    onShareCaptureFailed: (Throwable) -> Unit,
) {
    val scrollState = rememberScrollState()
    val screenshotLayer = rememberGraphicsLayer()
    val coroutineScope = rememberCoroutineScope()
    val screenshotBackgroundColor = MaterialTheme.colorScheme.surface
    val presentationDates = resolveStatisticsSnapshotPresentationDates(stats)

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.Small),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .drawWithContent {
                        screenshotLayer.record {
                            drawRect(screenshotBackgroundColor)
                            this@drawWithContent.drawContent()
                        }
                        drawLayer(screenshotLayer)
                    },
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
        ) {
            StatisticsOverviewSection(stats = stats)
            StatisticsActivitySection(stats = stats, today = presentationDates.today)
            StatisticsTimeSection(stats = stats)
            StatisticsReportsSection(
                stats = stats,
                today = presentationDates.today,
                weekDays = presentationDates.weekDays,
            )
            StatisticsTagsSection(stats = stats)

            Spacer(modifier = Modifier.height(AppSpacing.ExtraLarge))
        }
        FilledTonalButton(
            onClick = {
                coroutineScope.launch {
                    runCatching {
                        captureStatisticsPngSource(screenshotLayer)
                    }.onSuccess(onShareImageCaptured)
                        .onFailure(onShareCaptureFailed)
                }
            },
            modifier =
                Modifier
                    .fillMaxWidth(),
        ) {
            Icon(
                Icons.Outlined.Share,
                contentDescription = null,
            )
            Spacer(modifier = Modifier.padding(horizontal = AppSpacing.ExtraSmall))
            Text(stringResource(R.string.stats_share_current_page))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatisticsOverviewSection(stats: MemoStatistics) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        SectionHeader(text = stringResource(R.string.stats_section_overview))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
        ) {
            StatCard(
                value = stats.totalMemos.toString(),
                label = stringResource(R.string.stats_total_memos),
                modifier = Modifier.weight(1f),
            )
            StatCard(
                value = formatStatisticsNumber(stats.totalWords),
                label = stringResource(R.string.stats_total_words),
                modifier = Modifier.weight(1f),
            )
            StatCard(
                value = stats.activeDays.toString(),
                label = stringResource(R.string.stats_active_days),
                modifier = Modifier.weight(1f),
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
        ) {
            StatCard(
                value = "%.1f".format(stats.averageWordsPerMemo),
                label = stringResource(R.string.stats_avg_words),
                modifier = Modifier.weight(1f),
            )
            StatCard(
                value = stats.totalTags.toString(),
                label = stringResource(R.string.stats_total_tags),
                modifier = Modifier.weight(1f),
            )
            StatCard(
                value = formatStatisticsNumber(stats.totalCharacters),
                label = stringResource(R.string.stats_total_chars),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatisticsActivitySection(
    stats: MemoStatistics,
    today: LocalDate,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        SectionHeader(text = stringResource(R.string.stats_section_activity))
        CalendarHeatmap(
            memoCountByDate =
                stats.memoCountByDate
                    .filterKeys { !it.isAfter(today) }
                    .toImmutableMap(),
            today = today,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatisticsTimeSection(stats: MemoStatistics) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        SectionHeader(text = stringResource(R.string.stats_section_time))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
        ) {
            StatCard(
                value = formatStatisticsDailyMemoTime(stats.earliestDailyMemoTime),
                label = stringResource(R.string.stats_earliest_note_time),
                modifier = Modifier.weight(1f),
            )
            StatCard(
                value = formatStatisticsDailyMemoTime(stats.latestDailyMemoTime),
                label = stringResource(R.string.stats_latest_note_time),
                modifier = Modifier.weight(1f),
            )
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            when (resolveStatisticsTimeDistributionLayout(widthDp = maxWidth.value.roundToInt())) {
                StatisticsTimeDistributionLayout.Stacked -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.MediumSmall),
                    ) {
                        WeeklyHourHeatmap(
                            weeklyHourDistribution =
                                stats.weeklyHourDistribution
                                    .mapValues { (_, hourlyMap) -> hourlyMap.toImmutableMap() }
                                    .toImmutableMap(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        HourlyActivityChart(
                            hourlyDistribution = stats.hourlyDistribution.toImmutableMap(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                StatisticsTimeDistributionLayout.TwoColumn -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
                        verticalAlignment = Alignment.Top,
                    ) {
                        WeeklyHourHeatmap(
                            weeklyHourDistribution =
                                stats.weeklyHourDistribution
                                    .mapValues { (_, hourlyMap) -> hourlyMap.toImmutableMap() }
                                    .toImmutableMap(),
                            modifier = Modifier.weight(1f),
                        )
                        HourlyActivityChart(
                            hourlyDistribution = stats.hourlyDistribution.toImmutableMap(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatisticsReportsSection(
    stats: MemoStatistics,
    today: LocalDate,
    weekDays: Int,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        SectionHeader(text = stringResource(R.string.stats_section_reports))
        PeriodReportCard(
            title = stringResource(R.string.stats_this_week),
            count = stats.thisWeekCount,
            previousCount = stats.lastWeekCount,
            periodDays = weekDays,
        )
        PeriodReportCard(
            title = stringResource(R.string.stats_this_month),
            count = stats.thisMonthCount,
            previousCount = stats.lastMonthCount,
            periodDays = today.dayOfMonth,
        )
        PeriodReportCard(
            title = stringResource(R.string.stats_this_year),
            count = stats.thisYearCount,
            previousCount = stats.lastYearCount,
            periodDays = today.dayOfYear,
        )
    }
}

@Composable
private fun StatisticsTagsSection(stats: MemoStatistics) {
    if (stats.tagCounts.isEmpty()) {
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        SectionHeader(text = stringResource(R.string.stats_section_tags))
        TagDistributionChart(
            slices =
                stats.tagCounts
                    .map { tagCount -> ChartTagSlice(name = tagCount.name, count = tagCount.count) }
                    .toImmutableList(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
