package com.lomo.app.widget

import com.lomo.app.repository.AppWidgetRepository
import com.lomo.app.util.MarkdownCleanupFormatter
import com.lomo.domain.model.DailyReviewCollectionSource
import com.lomo.domain.model.DailyReviewOptions
import com.lomo.domain.model.DailyReviewSort
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.SecuritySessionState
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.MemoListQueryRepository
import com.lomo.domain.repository.MemoTaskRepository
import com.lomo.domain.repository.SecuritySessionPolicy
import com.lomo.domain.usecase.DailyReviewQueryUseCase
import com.lomo.domain.usecase.MemoStatisticsUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

/**
 * Read-only query, statistics, review, and config sources projected into the durable widget snapshot.
 */
internal class WidgetSnapshotSources(
    val listQueryRepository: MemoListQueryRepository,
    val taskRepository: MemoTaskRepository,
    val statisticsUseCase: MemoStatisticsUseCase,
    val dailyReviewQueryUseCase: DailyReviewQueryUseCase,
    val appConfigRepository: AppConfigRepository,
)

/**
 * Keeps Glance widgets on the store publication clock. Mutations must not command a widget refresh.
 * The engine-owning process writes a snapshot that projection-only processes can render.
 *
 * Commit publications are debounced into one write per window; mount/security transitions write
 * immediately because privacy and workspace availability cannot wait out a timer. Every write is
 * stamped with the durable core revision it reflects so a restarted binder reconciles the persisted
 * file against the live mount instead of trusting wall time.
 */
internal class WidgetProjectionBinder(
    scope: CoroutineScope,
    private val sources: WidgetSnapshotSources,
    private val appWidgetRepository: AppWidgetRepository,
    private val snapshotStore: WidgetGlanceSnapshotStore,
    private val engineReadiness: EngineReadinessRepository,
    private val securitySession: SecuritySessionPolicy,
    private val debounceMillis: Long = WIDGET_PROJECTION_DEBOUNCE_MILLIS,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val refreshMutex = Mutex()
    private val lastObservedStamp = AtomicLong(0L)

    /**
     * The rendered widget surface is the availability plus every widget payload slice; workspace
     * id and projection stamp are durable receipts that do not change widget pixels. A refresh
     * whose signature matches the last notified one skips the remote redraw entirely — widgets
     * re-read the same content — while the file write still advances the stamped revision.
     * The signature is updated only after a successful notify so a failed redraw is retried on
     * the next refresh instead of being forgotten.
     */
    private var lastNotifiedRenderSignature: WidgetRenderSignature? = null

    init {
        // Commit-driven ticks: debounce into one snapshot write per window.
        scope.launch {
            sources.listQueryRepository
                .observeListProjection()
                .collectLatest { publication ->
                    lastObservedStamp.set(publication.coreRevision)
                    delay(debounceMillis)
                    refreshSnapshot()
                }
        }
        // State transitions (mount admission, lock preference) write immediately: a privacy or
        // availability change must not wait out the debounce window, and the first emission after
        // process start reconciles whatever stamp the file still carries.
        scope.launch {
            combine(
                engineReadiness.mount,
                securitySession.observe(),
            ) { mount, session -> mount to session }
                .collectLatest {
                    refreshSnapshot()
                }
        }
    }

    /** Immediately reconciles and publishes the snapshot (used after interactive widget mutations). */
    suspend fun refreshNow() {
        refreshSnapshot()
    }

    private suspend fun refreshSnapshot() {
        try {
            refreshMutex.withLock {
                val mount = engineReadiness.mount.value
                val session = securitySession.current()
                if (mount.readiness is EngineReadiness.Opening) {
                    reconcileOpeningMount(session)
                    return@withLock
                }
                val availability =
                    when {
                        !mount.admitsProjectionReads -> WidgetSnapshotAvailability.UNAVAILABLE

                        session != SecuritySessionState.LockOff ->
                            WidgetSnapshotAvailability.REDACTED

                        else -> WidgetSnapshotAvailability.READY
                    }
                val payload =
                    when (availability) {
                        WidgetSnapshotAvailability.UNAVAILABLE -> WidgetSnapshotPayload.Empty

                        WidgetSnapshotAvailability.REDACTED ->
                            WidgetSnapshotPayload(
                                items = snapshotItems(redacted = true),
                            )

                        WidgetSnapshotAvailability.READY -> buildReadyPayload()
                    }
                writeAndNotifyIfChanged(
                    workspaceId = mount.authority?.workspaceId,
                    projectionStamp = lastObservedStamp.get(),
                    availability = availability,
                    payload = payload,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Timber.w("Widget projection refresh failed: %s", failure.javaClass.simpleName)
        }
    }

    /**
     * Cold background wakes (for example [androidx.glance.appwidget.action.ActionCallback]) leave
     * [EngineReadiness.Opening] unsettled without an Activity first frame. Preserve any valid
     * durable snapshot instead of overwriting it with UNAVAILABLE, while still scrubbing sensitive
     * bodies immediately if the security session is locked or unknown.
     */
    private suspend fun reconcileOpeningMount(session: SecuritySessionState) {
        val existing = snapshotStore.read()
        if (existing !is WidgetGlanceSnapshot.Ready) {
            return
        }
        if (session != SecuritySessionState.LockOff && existing.availability == WidgetSnapshotAvailability.READY) {
            val redactedItems =
                existing.items.map { item ->
                    item.copy(
                        previewText = "",
                        tags = emptyList(),
                        isPinned = false,
                    )
                }
            writeAndNotifyIfChanged(
                workspaceId = existing.workspaceId,
                projectionStamp = existing.projectionStamp,
                availability = WidgetSnapshotAvailability.REDACTED,
                payload = WidgetSnapshotPayload(items = redactedItems),
            )
        }
    }

    private suspend fun writeAndNotifyIfChanged(
        workspaceId: String?,
        projectionStamp: Long,
        availability: WidgetSnapshotAvailability,
        payload: WidgetSnapshotPayload,
    ) {
        snapshotStore.write(
            workspaceId = workspaceId,
            projectionStamp = projectionStamp,
            availability = availability,
            items = payload.items,
            tasks = payload.tasks,
            stats = payload.stats,
            reviews = payload.reviews,
        )
        val renderSignature =
            WidgetRenderSignature(
                availability = availability,
                items = payload.items,
                tasks = payload.tasks,
                stats = payload.stats,
                reviews = payload.reviews,
            )
        if (lastNotifiedRenderSignature != renderSignature) {
            // The snapshot is durable before the host is asked to redraw.
            appWidgetRepository.updateAllWidgets()
            lastNotifiedRenderSignature = renderSignature
        }
    }

    private suspend fun buildReadyPayload(): WidgetSnapshotPayload {
        val items = snapshotItems(redacted = false)
        val tasks = snapshotTasks()
        val (stats, asOfDate) = snapshotStats()
        val reviews = snapshotReviews(asOfDate)
        return WidgetSnapshotPayload(
            items = items,
            tasks = tasks,
            stats = stats,
            reviews = reviews,
        )
    }

    private suspend fun snapshotItems(redacted: Boolean): List<WidgetGlanceSnapshotItem> =
        sources.listQueryRepository.getRecentMemos(WIDGET_MEMO_LIMIT).map { memo ->
            val presentation =
                if (redacted) {
                    null
                } else {
                    resolveWidgetMemoItemPresentation(memo, nowMillis())
                }
            WidgetGlanceSnapshotItem(
                id = memo.id,
                timestampMillis = memo.timestamp,
                previewText = presentation?.previewText.orEmpty(),
                tags =
                if (redacted) {
                    emptyList()
                } else {
                    memo.tags.asSequence().filter(String::isNotBlank).take(WIDGET_TAG_LIMIT_PER_ITEM).toList()
                },
                isPinned = !redacted && memo.isPinned,
            )
        }

    private suspend fun snapshotTasks(): List<WidgetGlanceTaskItem> {
        val allTasks = sources.taskRepository.listTasks()
        val active = allTasks.filterNot { it.done }
        val completed = allTasks.filter { it.done }
        return (active + completed)
            .take(WIDGET_TASK_LIMIT)
            .map { task ->
                WidgetGlanceTaskItem(
                    memoId = task.memoId,
                    lineIndex = task.lineIndex,
                    done = task.done,
                    text = formatWidgetTaskText(task.text),
                    dateKey = task.sourcePath.substringAfterLast('/').removeSuffix(MARKDOWN_FILE_EXTENSION),
                )
            }
    }

    private suspend fun snapshotStats(): Pair<WidgetGlanceStats, LocalDate> {
        val memoStats = sources.statisticsUseCase()
        val asOfDate = memoStats.asOfDate
        val startDate = asOfDate.minusDays((WIDGET_HEATMAP_DAY_LIMIT - 1).toLong())
        val thresholds = sources.appConfigRepository.getCalendarHeatmapThresholds().first()
        val heatmapDays =
            memoStats.memoCountByDate.entries
                .asSequence()
                .filter { (date, count) -> !date.isBefore(startDate) && !date.isAfter(asOfDate) && count > 0 }
                .sortedBy { (date, _) -> date }
                .map { (date, count) -> WidgetGlanceHeatmapDay(epochDay = date.toEpochDay(), count = count) }
                .toList()
        return WidgetGlanceStats(
            totalMemos = memoStats.totalMemos,
            todayCount = memoStats.memoCountByDate[asOfDate] ?: 0,
            thisWeekCount = memoStats.thisWeekCount,
            currentStreak = memoStats.currentStreak,
            longestStreak = memoStats.longestStreak,
            activeDays = memoStats.activeDays,
            asOfEpochDay = asOfDate.toEpochDay(),
            level1Max = thresholds.level1Max,
            level2Max = thresholds.level2Max,
            level3Max = thresholds.level3Max,
            heatmapDays = heatmapDays,
        ) to asOfDate
    }

    private suspend fun snapshotReviews(asOfDate: LocalDate): List<WidgetGlanceReviewItem> {
        val page =
            sources.dailyReviewQueryUseCase.loadPage(
                DailyReviewCollectionSource(
                    sessionDate = asOfDate,
                    seed = asOfDate.toEpochDay(),
                    options = DailyReviewOptions(sort = DailyReviewSort.RANDOM),
                    pageSize = WIDGET_REVIEW_LIMIT,
                ),
            )
        val zone = zoneProvider()
        return page.memos.take(WIDGET_REVIEW_LIMIT).map { memo ->
            val clean = MarkdownCleanupFormatter.collapseSpacing(memo.content)
            val preview =
                clean.take(WIDGET_REVIEW_PREVIEW_LENGTH).let { sliced ->
                    if (clean.length > WIDGET_REVIEW_PREVIEW_LENGTH) "$sliced..." else sliced
                }
            val dateKey =
                memo.localDate?.toString()
                    ?: memo.dateKey.ifBlank {
                        Instant.ofEpochMilli(memo.timestamp).atZone(zone).toLocalDate().toString()
                    }
            WidgetGlanceReviewItem(
                id = memo.id,
                timestampMillis = memo.timestamp,
                previewText = preview,
                dateKey = dateKey,
                tags = memo.tags.asSequence().filter(String::isNotBlank).take(WIDGET_TAG_LIMIT_PER_ITEM).toList(),
            )
        }
    }

    private fun formatWidgetTaskText(raw: String): String {
        val collapsed = MarkdownCleanupFormatter.collapseSpacing(raw)
        return collapsed.take(WIDGET_TASK_TEXT_LIMIT).let { clipped ->
            if (collapsed.length > WIDGET_TASK_TEXT_LIMIT) "$clipped..." else clipped
        }
    }

    companion object {
        const val WIDGET_PROJECTION_DEBOUNCE_MILLIS = 500L
        private const val WIDGET_TASK_TEXT_LIMIT = 140
        private const val WIDGET_REVIEW_PREVIEW_LENGTH = 220
        private const val MARKDOWN_FILE_EXTENSION = ".md"
    }
}

private data class WidgetSnapshotPayload(
    val items: List<WidgetGlanceSnapshotItem> = emptyList(),
    val tasks: List<WidgetGlanceTaskItem> = emptyList(),
    val stats: WidgetGlanceStats? = null,
    val reviews: List<WidgetGlanceReviewItem> = emptyList(),
) {
    companion object {
        val Empty = WidgetSnapshotPayload()
    }
}

/** What widgets actually draw; identical signatures mean identical remote output. */
private data class WidgetRenderSignature(
    val availability: WidgetSnapshotAvailability,
    val items: List<WidgetGlanceSnapshotItem>,
    val tasks: List<WidgetGlanceTaskItem>,
    val stats: WidgetGlanceStats?,
    val reviews: List<WidgetGlanceReviewItem>,
)
