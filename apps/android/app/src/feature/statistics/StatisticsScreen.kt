package com.lomo.app.feature.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.util.injectedKoinViewModel
import com.lomo.domain.model.CalendarHeatmapThresholds
import com.lomo.domain.model.MemoStatistics
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.component.common.EmptyState
import com.lomo.ui.component.common.ExpressiveContainedLoadingIndicator
import com.lomo.ui.component.common.LomoIconButton
import com.lomo.ui.component.common.LomoTextButton
import com.lomo.ui.component.stats.CalendarHeatmap
import com.lomo.ui.component.stats.ChartTagSlice
import com.lomo.ui.component.stats.HourlyActivityChart
import com.lomo.ui.component.stats.PeriodReportCard
import com.lomo.ui.component.stats.StatCard
import com.lomo.ui.component.stats.StatCardGrid
import com.lomo.ui.component.stats.TagDistributionChart
import com.lomo.ui.component.stats.WeeklyHourHeatmap
import com.lomo.ui.icon.LomoIcon
import com.lomo.ui.icon.LomoIcons
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatisticsViewModel = injectedKoinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val appPreferences by viewModel.appPreferences.collectAsStateWithLifecycle()
    val shareImageEvents by viewModel.shareImageEvents.collectAsStateWithLifecycle()
    val shareErrorMessage by viewModel.shareErrorMessage.collectAsStateWithLifecycle()
    val haptic = LocalAppHapticFeedback.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val screenshotLayer = rememberGraphicsLayer()
    val shareScope = rememberCoroutineScope()
    val shareCurrentPage: () -> Unit = {
        haptic.medium()
        shareScope.launch {
            try {
                viewModel.shareStatisticsImage(captureStatisticsPngSource(screenshotLayer))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                viewModel.reportShareFailure(error)
            }
        }
    }

    StatisticsShareEffects(
        context = context,
        shareImageEvents = shareImageEvents,
        shareErrorMessage = shareErrorMessage,
        snackbarHostState = snackbarHostState,
        onShareEventConsumed = viewModel::consumeShareImageEvent,
        onShareFailed = viewModel::reportShareFailure,
        onShareErrorConsumed = viewModel::clearShareError,
    )

    Scaffold(
        modifier =
        modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .benchmarkAnchorRoot(BenchmarkAnchorContract.STATISTICS_ROOT),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            StatisticsTopBar(
                isReady = uiState is StatisticsScreenState.Ready,
                scrollBehavior = scrollBehavior,
                onBackClick = {
                    haptic.medium()
                    onBackClick()
                },
                onShareClick = shareCurrentPage,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            com.lomo.ui.component.common.LomoStateContent(
                state = uiState,
                contentKey = { it::class },
                modifier = Modifier.fillMaxSize(),
            ) { state ->
                when (state) {
                    is StatisticsScreenState.Loading -> ExpressiveContainedLoadingIndicator()

                    is StatisticsScreenState.Error -> {
                        EmptyState(
                            icon = LomoIcons.Error,
                            title = stringResource(R.string.stats_load_failed_title),
                            description = stringResource(R.string.stats_load_failed_description),
                            action = {
                                LomoTextButton(onClick = viewModel::retry) {
                                    Text(stringResource(R.string.action_retry))
                                }
                            },
                        )
                    }

                    is StatisticsScreenState.Ready -> {
                        StatisticsContent(
                            stats = state.statistics,
                            calendarHeatmapThresholds = appPreferences.calendarHeatmapThresholds,
                            screenshotLayer = screenshotLayer,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatisticsTopBar(
    isReady: Boolean,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    onBackClick: () -> Unit,
    onShareClick: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                text = stringResource(R.string.sidebar_statistics),
                style = MaterialTheme.typography.titleLargeEmphasized,
            )
        },
        navigationIcon = {
            LomoIconButton(onClick = onBackClick) {
                LomoIcon(
                    LomoIcons.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
        },
        actions = {
            if (isReady) {
                LomoIconButton(onClick = onShareClick) {
                    LomoIcon(
                        LomoIcons.Share,
                        contentDescription = stringResource(R.string.stats_share_current_page),
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun StatisticsContent(
    stats: MemoStatistics,
    calendarHeatmapThresholds: CalendarHeatmapThresholds,
    screenshotLayer: androidx.compose.ui.graphics.layer.GraphicsLayer,
) {
    val scrollState = rememberScrollState()
    val screenshotBackgroundColor = MaterialTheme.colorScheme.surface
    val presentationDates = resolveStatisticsSnapshotPresentationDates(stats)

    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
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
                }
                .padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
        ) {
            StatisticsOverviewSection(stats = stats)
            StatisticsActivitySection(
                stats = stats,
                today = presentationDates.today,
                calendarHeatmapThresholds = calendarHeatmapThresholds,
            )
            StatisticsTimeSection(stats = stats)
            StatisticsReportsSection(
                stats = stats,
                today = presentationDates.today,
                weekDays = presentationDates.weekDays,
            )
            StatisticsTagsSection(stats = stats)
        }
        Spacer(modifier = Modifier.height(AppSpacing.Medium))
    }
}

@Composable
private fun StatisticsOverviewSection(stats: MemoStatistics) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        SectionHeader(text = stringResource(R.string.stats_section_overview))
        StatCardGrid(modifier = Modifier.fillMaxWidth()) {
            StatCard(
                value = stats.totalMemos.toString(),
                label = stringResource(R.string.stats_total_memos),
                icon = LomoIcons.Notes,
            )
            StatCard(
                value = formatStatisticsNumber(stats.totalWords),
                label = stringResource(R.string.stats_total_words),
                icon = LomoIcons.ShortText,
            )
            StatCard(
                value = stats.activeDays.toString(),
                label = stringResource(R.string.stats_active_days),
                icon = LomoIcons.CalendarMonth,
            )
            StatCard(
                value = "%.1f".format(Locale.US, stats.averageWordsPerMemo),
                label = stringResource(R.string.stats_avg_words),
                icon = LomoIcons.Analytics,
            )
            StatCard(
                value = stats.totalTags.toString(),
                label = stringResource(R.string.stats_total_tags),
                icon = LomoIcons.Tag,
            )
            StatCard(
                value = formatStatisticsNumber(stats.totalCharacters),
                label = stringResource(R.string.stats_total_chars),
                icon = LomoIcons.TextFields,
            )
        }
    }
}

@Composable
private fun StatisticsActivitySection(
    stats: MemoStatistics,
    today: LocalDate,
    calendarHeatmapThresholds: CalendarHeatmapThresholds,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        SectionHeader(text = stringResource(R.string.stats_section_activity))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = AppShapes.LargeIncreased,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Box(modifier = Modifier.padding(AppSpacing.Medium)) {
                CalendarHeatmap(
                    memoCountByDate =
                    stats.memoCountByDate
                        .filterKeys { !it.isAfter(today) }
                        .toImmutableMap(),
                    today = today,
                    thresholds = calendarHeatmapThresholds,
                    yearBackgroundColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun StatisticsTimeSection(stats: MemoStatistics) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        SectionHeader(text = stringResource(R.string.stats_section_time))
        StatCardGrid(modifier = Modifier.fillMaxWidth()) {
            StatCard(
                value = formatStatisticsDailyMemoTime(stats.earliestDailyMemoTime),
                label = stringResource(R.string.stats_earliest_note_time),
                icon = LomoIcons.WbSunny,
            )
            StatCard(
                value = formatStatisticsDailyMemoTime(stats.latestDailyMemoTime),
                label = stringResource(R.string.stats_latest_note_time),
                icon = LomoIcons.NightsStay,
            )
        }
        val weeklyHourDistribution =
            remember(stats.weeklyHourDistribution) {
                stats.weeklyHourDistribution
                    .mapValues { (_, hourlyMap) -> hourlyMap.toImmutableMap() }
                    .toImmutableMap()
            }
        val hourlyDistribution =
            remember(stats.hourlyDistribution) {
                stats.hourlyDistribution.toImmutableMap()
            }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            when (resolveStatisticsTimeDistributionLayout(widthDp = maxWidth.value.roundToInt())) {
                StatisticsTimeDistributionLayout.Stacked -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = AppShapes.LargeIncreased,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(
                            modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(AppSpacing.Medium),
                            verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
                        ) {
                            WeeklyHourHeatmap(
                                weeklyHourDistribution = weeklyHourDistribution,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            HourlyActivityChart(
                                hourlyDistribution = hourlyDistribution,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                StatisticsTimeDistributionLayout.TwoColumn -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = AppShapes.LargeIncreased,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Box(modifier = Modifier.padding(AppSpacing.Medium)) {
                                WeeklyHourHeatmap(
                                    weeklyHourDistribution = weeklyHourDistribution,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = AppShapes.LargeIncreased,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Box(modifier = Modifier.padding(AppSpacing.Medium)) {
                                HourlyActivityChart(
                                    hourlyDistribution = hourlyDistribution,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatisticsReportsSection(stats: MemoStatistics, today: LocalDate, weekDays: Int) {
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
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = AppShapes.LargeIncreased,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Box(modifier = Modifier.padding(AppSpacing.Medium)) {
                TagDistributionChart(
                    slices =
                    stats.tagCounts
                        .map { tagCount -> ChartTagSlice(name = tagCount.name, count = tagCount.count) }
                        .toImmutableList(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMediumEmphasized,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = AppSpacing.ExtraSmall),
    )
}
