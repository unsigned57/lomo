package com.lomo.app.widget

// architectural-boundary-check: the locked-session case asserts on the persisted snapshot
// bytes — no memo body may reach the durable file — which is a format boundary, not a
// Kotlin source-string assertion.

/*
 * Behavior Contract:
 * Test Change Justification:
 * Reason category: explicit activation input adaptation.
 * Old behavior/assertion being replaced: root-only activations implicitly read attachment candidates from a journal.
 * Why old assertion is no longer correct: activation now receives a prepared attachment change explicitly;
 * these existing root-only calls select the committed attachment configuration with a null change.
 * Coverage preserved by: every existing mount, state and failure assertion is retained without weakening.
 * Why this is not fitting the test to the implementation: stale pending candidates must not influence restoration.
 * TDD proof: Regression preservation - transaction-green-first.log and verification-green.log under
 * target/lomo/reports/manual/directory-selection/; index-validation-red.log exposes premature activation.
 * - Unit under test: WidgetProjectionBinder
 * - Owning layer: app
 * - Priority tier: P0
 * - Capability: the engine-owning process keeps the widget snapshot on the durable projection stamp.
 *   Publications write bounded snapshots behind one debounce window; mount and security-state
 *   transitions write immediately; widget redraw is requested only after the snapshot file is
 *   durable; a non-lock-off session never persists body text.
 *
 * Scenarios:
 * - Given rapid list publications, when they land inside one debounce window, then the store is
 *   written once and widgets are refreshed after the write.
 * - Given a locked or unknown security session, when a snapshot is produced, then availability is
 *   REDACTED and no memo body, task text, statistics counts, or review text reach the file.
 * - Given a ready workspace with memos, tasks, statistics and review candidates, when refreshed,
 *   then all four widget slices (recent items with tags/pins, ordered tasks, stats & heatmap, and
 *   review cards) populate the snapshot.
 * - Given a task/stats change when recent items stay identical, then the expanded render signature
 *   still triggers a widget redraw.
 * Observable outcomes: persisted snapshot slices and redaction, redraw ordering, and deduplication.
 * Excludes: launcher rendering and cross-process Android broadcast delivery.
 *
 * Test Change Justification:
 * - Reason category: product/domain contract changed.
 * - Old behavior/assertion being replaced: WidgetProjectionBinder only synced 3 recent memo items.
 * - Why old assertion is no longer correct: the binder now populates schema v3 multi-widget slices (items up to 6 with tags/pins, tasks, heatmap/streak stats, and review candidates) with full redaction when locked and signature tracking across all slices.
 * - Coverage preserved by: all existing debounce, lock-redaction, recovery, deduplication, ordering, failure-survival and cancellation cases remain untouched in intent.
 * - Why this is not fitting the test to the implementation: all 4 widget sections must remain synchronized and redacted behind the projection publication boundary.
 */

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.app.repository.AppWidgetRepository
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.app.testing.fakes.FakeEngineReadinessRepository
import com.lomo.app.testing.fakes.FakeMemoQueryRepository
import com.lomo.app.testing.fakes.FakeMemoStatisticsRepository
import com.lomo.app.testing.fakes.FakeMemoStore
import com.lomo.domain.model.CredentialReadAuthorization
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoProjectionPublication
import com.lomo.domain.model.MemoSidebarStatistics
import com.lomo.domain.model.MemoTask
import com.lomo.domain.model.SecuritySessionState
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.MemoListQueryRepository
import com.lomo.domain.repository.MemoTaskRepository
import com.lomo.domain.repository.SecuritySessionPolicy
import com.lomo.domain.usecase.DailyReviewQueryUseCase
import com.lomo.domain.usecase.MemoStatisticsDateSnapshot
import com.lomo.domain.usecase.MemoStatisticsUseCase
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetProjectionBinderTest : AppFunSpec() {
    init {
        test(
            "given rapid publications inside one debounce window when the window elapses then the snapshot is written once on the latest stamp",
        ) {
            runTest {
                val dir = createTempDirectory("widget-binder-debounce").toFile()
                try {
                    val store = snapshotStore(dir, testScheduler)
                    val widgets = RecordingWidgetRepository()
                    val ticking = TickingMemoListQueryRepository()
                    ticking.recent = listOf(sampleMemo("a", 1_000L))

                    binder(ticking, widgets, store)
                    runCurrent()
                    widgets.updated shouldBe 1

                    ticking.publications.emit(MemoProjectionPublication(coreRevision = 1L))
                    ticking.publications.emit(MemoProjectionPublication(coreRevision = 2L))
                    ticking.publications.emit(MemoProjectionPublication(coreRevision = 3L))
                    advanceTimeBy(WidgetProjectionBinder.WIDGET_PROJECTION_DEBOUNCE_MILLIS)
                    runCurrent()

                    // The debounced refresh rewrites the durable snapshot on the latest stamp but
                    // does not redraw the widget: the rendered items did not change.
                    widgets.updated shouldBe 1
                    val snapshot = store.read() as WidgetGlanceSnapshot.Ready
                    snapshot.projectionStamp shouldBe 3L
                    snapshot.items.map { it.id } shouldBe listOf("a")
                } finally {
                    dir.deleteRecursively()
                }
            }
        }

        test("given a locked session when the snapshot is produced then no memo body reaches the file") {
            runTest {
                val dir = createTempDirectory("widget-binder-locked").toFile()
                try {
                    val store = snapshotStore(dir, testScheduler)
                    val widgets = RecordingWidgetRepository()
                    val ticking = TickingMemoListQueryRepository()
                    ticking.recent = listOf(sampleMemo("secret", 1_000L, content = "private body"))

                    binder(
                        ticking,
                        widgets,
                        store,
                        session = RecordingSecuritySessionPolicy(SecuritySessionState.Locked),
                    )
                    runCurrent()

                    val snapshot = store.read() as WidgetGlanceSnapshot.Ready
                    snapshot.availability shouldBe WidgetSnapshotAvailability.REDACTED
                    snapshot.items.map { it.id } shouldBe listOf("secret")
                    snapshot.items.map { it.previewText } shouldBe listOf("")
                    storeFile(dir).readText().contains("private body") shouldBe false
                } finally {
                    dir.deleteRecursively()
                }
            }
        }

        test("given an unknown session when the snapshot is produced then the file carries no body text") {
            runTest {
                val dir = createTempDirectory("widget-binder-unknown").toFile()
                try {
                    val store = snapshotStore(dir, testScheduler)
                    val widgets = RecordingWidgetRepository()
                    val ticking = TickingMemoListQueryRepository()
                    ticking.recent = listOf(sampleMemo("a", 1_000L))

                    binder(
                        ticking,
                        widgets,
                        store,
                        session = RecordingSecuritySessionPolicy(SecuritySessionState.Unknown),
                    )
                    runCurrent()

                    val snapshot = store.read() as WidgetGlanceSnapshot.Ready
                    snapshot.availability shouldBe WidgetSnapshotAvailability.REDACTED
                    snapshot.items.single().previewText shouldBe ""
                } finally {
                    dir.deleteRecursively()
                }
            }
        }

        test(
            "given a lock transition when it publishes then a redacted snapshot is written without a publication tick",
        ) {
            runTest {
                val dir = createTempDirectory("widget-binder-lockflip").toFile()
                try {
                    val store = snapshotStore(dir, testScheduler)
                    val widgets = RecordingWidgetRepository()
                    val ticking = TickingMemoListQueryRepository()
                    val session = RecordingSecuritySessionPolicy(SecuritySessionState.LockOff)
                    ticking.recent = listOf(sampleMemo("a", 1_000L, content = "plain body"))

                    binder(ticking, widgets, store, session = session, debounceMillis = 0L)
                    runCurrent()

                    val before = store.read() as WidgetGlanceSnapshot.Ready
                    before.availability shouldBe WidgetSnapshotAvailability.READY
                    before.items.single().previewText shouldBe "plain body"

                    session.publish(SecuritySessionState.Locked)
                    runCurrent()

                    val after = store.read() as WidgetGlanceSnapshot.Ready
                    after.availability shouldBe WidgetSnapshotAvailability.REDACTED
                    after.items.single().previewText shouldBe ""
                    widgets.updated shouldBe 2
                } finally {
                    dir.deleteRecursively()
                }
            }
        }

        test("given a mount that does not admit reads when refreshed then the snapshot is unavailable with no items") {
            runTest {
                val dir = createTempDirectory("widget-binder-unmounted").toFile()
                try {
                    val store = snapshotStore(dir, testScheduler)
                    val widgets = RecordingWidgetRepository()
                    val ticking = TickingMemoListQueryRepository()
                    ticking.recent = listOf(sampleMemo("a", 1_000L))
                    val engineReadiness = FakeEngineReadinessRepository()
                    engineReadiness.clearWorkspace()

                    binder(ticking, widgets, store, engineReadiness = engineReadiness)
                    runCurrent()

                    val snapshot = store.read() as WidgetGlanceSnapshot.Ready
                    snapshot.availability shouldBe WidgetSnapshotAvailability.UNAVAILABLE
                    snapshot.items shouldHaveSize 0
                } finally {
                    dir.deleteRecursively()
                }
            }
        }

        test("given a recovered mount when a publication arrives then the pending snapshot update completes") {
            runTest {
                val dir = createTempDirectory("widget-binder-recovery").toFile()
                try {
                    val store = snapshotStore(dir, testScheduler)
                    val widgets = RecordingWidgetRepository()
                    val ticking = TickingMemoListQueryRepository()
                    ticking.recent = listOf(sampleMemo("a", 1_000L))
                    val engineReadiness = FakeEngineReadinessRepository()
                    engineReadiness.clearWorkspace()

                    binder(ticking, widgets, store, engineReadiness = engineReadiness, debounceMillis = 0L)
                    runCurrent()
                    (store.read() as WidgetGlanceSnapshot.Ready).availability shouldBe
                        WidgetSnapshotAvailability.UNAVAILABLE

                    engineReadiness.activateWorkspace(StorageLocation("/tmp/ws"), mediaChange = null)
                    ticking.publications.emit(MemoProjectionPublication(coreRevision = 11L))
                    runCurrent()

                    val snapshot = store.read() as WidgetGlanceSnapshot.Ready
                    snapshot.availability shouldBe WidgetSnapshotAvailability.READY
                    snapshot.projectionStamp shouldBe 11L
                    snapshot.workspaceId shouldNotBe null
                    snapshot.items.map { it.id } shouldBe listOf("a")
                } finally {
                    dir.deleteRecursively()
                }
            }
        }

        test(
            "given an unchanged memo set when a publication refreshes then the widget redraw is skipped while the stamp still advances",
        ) {
            runTest {
                val dir = createTempDirectory("widget-binder-dedupe").toFile()
                try {
                    val store = snapshotStore(dir, testScheduler)
                    val widgets = RecordingWidgetRepository()
                    val ticking = TickingMemoListQueryRepository()
                    ticking.recent = listOf(sampleMemo("a", 1_000L))

                    binder(ticking, widgets, store, debounceMillis = 0L)
                    runCurrent()
                    widgets.updated shouldBe 1

                    ticking.publications.emit(MemoProjectionPublication(coreRevision = 9L))
                    runCurrent()

                    // The rendered signature (availability + items) is unchanged, so the remote
                    // redraw is skipped; the durable snapshot still records the latest revision.
                    widgets.updated shouldBe 1
                    (store.read() as WidgetGlanceSnapshot.Ready).projectionStamp shouldBe 9L

                    ticking.recent = listOf(sampleMemo("a", 1_000L), sampleMemo("b", 2_000L))
                    ticking.publications.emit(MemoProjectionPublication(coreRevision = 10L))
                    runCurrent()

                    widgets.updated shouldBe 2
                    (store.read() as WidgetGlanceSnapshot.Ready).items.map { it.id } shouldBe
                        listOf("a", "b")
                } finally {
                    dir.deleteRecursively()
                }
            }
        }

        test("given a publication when the snapshot is written then the widget redraw is requested after the write") {
            runTest {
                val dir = createTempDirectory("widget-binder-order").toFile()
                try {
                    val store = snapshotStore(dir, testScheduler)
                    val widgets = RecordingWidgetRepository()
                    val ticking = TickingMemoListQueryRepository()
                    ticking.recent = listOf(sampleMemo("a", 1_000L))
                    widgets.onUpdate = {
                        (store.read() as WidgetGlanceSnapshot.Ready).items.map { it.id } shouldBe listOf("a")
                    }

                    binder(ticking, widgets, store, debounceMillis = 0L)
                    runCurrent()

                    ticking.publications.emit(MemoProjectionPublication(coreRevision = 4L))
                    runCurrent()

                    // The publication left the rendered item set unchanged, so the redraw count
                    // stays at the initial write's notification; ordering still held there because
                    // onUpdate observed the durable snapshot.
                    widgets.updated shouldBe 1
                } finally {
                    dir.deleteRecursively()
                }
            }
        }

        test(
            "given a query failure when refresh throws then the binder skips the redraw and survives the next publication",
        ) {
            runTest {
                val dir = createTempDirectory("widget-binder-failure").toFile()
                try {
                    val store = snapshotStore(dir, testScheduler)
                    val widgets = RecordingWidgetRepository()
                    val ticking = TickingMemoListQueryRepository()
                    ticking.recent = listOf(sampleMemo("a", 1_000L))
                    ticking.failOnRecent = true

                    binder(ticking, widgets, store, debounceMillis = 0L)
                    runCurrent()
                    widgets.updated shouldBe 0
                    store.read() shouldBe WidgetGlanceSnapshot.Absent

                    ticking.failOnRecent = false
                    ticking.publications.emit(MemoProjectionPublication(coreRevision = 5L))
                    runCurrent()

                    widgets.updated shouldBe 1
                    (store.read() as WidgetGlanceSnapshot.Ready).items.map { it.id } shouldBe listOf("a")
                } finally {
                    dir.deleteRecursively()
                }
            }
        }

        test(
            "given a cancelled refresh when it rethrows then the pipeline survives and the next publication still writes",
        ) {
            runTest {
                val dir = createTempDirectory("widget-binder-cancel").toFile()
                try {
                    val store = snapshotStore(dir, testScheduler)
                    val widgets = RecordingWidgetRepository()
                    val ticking = TickingMemoListQueryRepository()
                    ticking.recent = listOf(sampleMemo("a", 1_000L))
                    ticking.cancelOnRecent = true

                    binder(ticking, widgets, store, debounceMillis = 0L)
                    runCurrent()
                    ticking.publications.emit(MemoProjectionPublication(coreRevision = 6L))
                    runCurrent()

                    // A cancelled refresh cancels that run only; collectLatest keeps the pipeline
                    // alive so the next publication still lands a snapshot.
                    ticking.cancelOnRecent = false
                    ticking.publications.emit(MemoProjectionPublication(coreRevision = 7L))
                    runCurrent()

                    widgets.updated shouldBe 1
                    val snapshot = store.read() as WidgetGlanceSnapshot.Ready
                    snapshot.projectionStamp shouldBe 7L
                    snapshot.items.map { it.id } shouldBe listOf("a")
                } finally {
                    dir.deleteRecursively()
                }
            }
        }

        test(
            "given unlocked workspace with memos, tasks, stats, and review candidates then all widget slices populate",
        ) {
            runTest {
                val dir = createTempDirectory("widget-binder-multi").toFile()
                try {
                    val store = snapshotStore(dir, testScheduler)
                    val widgets = RecordingWidgetRepository()
                    val memoStore = FakeMemoStore()
                    val today = LocalDate.of(2026, 11, 14)
                    val todayMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 3_600_000L
                    memoStore.setActiveMemos(
                        listOf(
                            Memo(
                                id = "m-1",
                                timestamp = todayMillis,
                                content = "Buy milk and ship #work #mobile",
                                rawContent = "Buy milk and ship #work #mobile",
                                dateKey = "2026-11-14",
                                localDate = today,
                                tags = listOf("work", "mobile"),
                                isPinned = true,
                            ),
                        ),
                    )
                    val ticking = TickingMemoListQueryRepository().apply { recent = memoStore.currentActiveMemos() }
                    val tasksRepo =
                        FakeTaskRepository(
                            listOf(
                                MemoTask("m-1", 1, true, "Done item", "2026-11-14.md"),
                                MemoTask("m-1", 0, false, "Active todo", "2026-11-14.md"),
                            ),
                        )
                    val statsRepo =
                        FixedStatsRepository(
                            com.lomo.domain.model.MemoStatistics.empty(today).copy(
                                totalMemos = 12,
                                thisWeekCount = 5,
                                currentStreak = 4,
                                longestStreak = 9,
                                activeDays = 10,
                                memoCountByDate =
                                mapOf(
                                    today to 2,
                                    today.minusDays(10) to 1,
                                    today.minusDays(200) to 4, // outside 126-day widget window
                                ),
                            ),
                        )

                    binder(
                        ticking = ticking,
                        widgets = widgets,
                        store = store,
                        taskRepository = tasksRepo,
                        memoStore = memoStore,
                        statisticsRepository = statsRepo,
                        today = today,
                        debounceMillis = 0L,
                    )
                    runCurrent()

                    val snapshot = store.read() as WidgetGlanceSnapshot.Ready
                    snapshot.availability shouldBe WidgetSnapshotAvailability.READY
                    snapshot.items.single().tags shouldBe listOf("work", "mobile")
                    snapshot.items.single().isPinned shouldBe true
                    snapshot.tasks.map { it.text to it.done } shouldBe
                        listOf("Active todo" to false, "Done item" to true)
                    snapshot.stats?.totalMemos shouldBe 12
                    snapshot.stats?.todayCount shouldBe 2
                    snapshot.stats?.thisWeekCount shouldBe 5
                    snapshot.stats?.currentStreak shouldBe 4
                    snapshot.stats?.heatmapDays?.map { it.epochDay } shouldBe
                        listOf(today.minusDays(10).toEpochDay(), today.toEpochDay())
                    snapshot.reviews.single().id shouldBe "m-1"
                } finally {
                    dir.deleteRecursively()
                }
            }
        }

        test(
            "given locked session when binder refreshes then all memo bodies, tasks, stats, and reviews are withheld",
        ) {
            runTest {
                val dir = createTempDirectory("widget-binder-redacted-all").toFile()
                try {
                    val file = storeFile(dir)
                    val store = snapshotStore(dir, testScheduler)
                    val widgets = RecordingWidgetRepository()
                    val memoStore = FakeMemoStore()
                    val today = LocalDate.of(2026, 11, 14)
                    memoStore.setActiveMemos(
                        listOf(
                            sampleMemo(
                                "m-1",
                                1_000L,
                                content = "secret journal",
                            ).copy(tags = listOf("private"), isPinned = true),
                        ),
                    )
                    val ticking = TickingMemoListQueryRepository().apply { recent = memoStore.currentActiveMemos() }
                    val tasksRepo = FakeTaskRepository(
                        listOf(MemoTask("m-1", 0, false, "secret task", "2026-11-14.md")),
                    )
                    val statsRepo =
                        FixedStatsRepository(
                            com.lomo.domain.model.MemoStatistics.empty(today).copy(
                                totalMemos = 99,
                                currentStreak = 30,
                                memoCountByDate = mapOf(today to 3),
                            ),
                        )

                    binder(
                        ticking = ticking,
                        widgets = widgets,
                        store = store,
                        session = RecordingSecuritySessionPolicy(SecuritySessionState.Locked),
                        taskRepository = tasksRepo,
                        memoStore = memoStore,
                        statisticsRepository = statsRepo,
                        today = today,
                        debounceMillis = 0L,
                    )
                    runCurrent()

                    val snapshot = store.read() as WidgetGlanceSnapshot.Ready
                    snapshot.availability shouldBe WidgetSnapshotAvailability.REDACTED
                    snapshot.items.single().previewText shouldBe ""
                    snapshot.items.single().tags shouldBe emptyList()
                    snapshot.items.single().isPinned shouldBe false
                    snapshot.tasks shouldBe emptyList()
                    snapshot.stats shouldBe null
                    snapshot.reviews shouldBe emptyList()
                    val raw = file.readText()
                    raw.contains("secret journal") shouldBe false
                    raw.contains("secret task") shouldBe false
                    raw.contains("private") shouldBe false
                } finally {
                    dir.deleteRecursively()
                }
            }
        }

        test("given task list changes when recent memos stay identical then widget redraw still fires") {
            runTest {
                val dir = createTempDirectory("widget-binder-task-delta").toFile()
                try {
                    val store = snapshotStore(dir, testScheduler)
                    val widgets = RecordingWidgetRepository()
                    val ticking = TickingMemoListQueryRepository().apply { recent = listOf(sampleMemo("a", 1_000L)) }
                    val tasksRepo =
                        FakeTaskRepository(
                            listOf(MemoTask("a", 0, false, "First task", "2026-11-14.md")),
                        )

                    binder(
                        ticking = ticking,
                        widgets = widgets,
                        store = store,
                        taskRepository = tasksRepo,
                        debounceMillis = 0L,
                    )
                    runCurrent()
                    widgets.updated shouldBe 1

                    tasksRepo.tasks = listOf(MemoTask("a", 0, true, "First task", "2026-11-14.md"))
                    ticking.publications.emit(MemoProjectionPublication(coreRevision = 2L))
                    runCurrent()

                    widgets.updated shouldBe 2
                    (store.read() as WidgetGlanceSnapshot.Ready).tasks.single().done shouldBe true
                } finally {
                    dir.deleteRecursively()
                }
            }
        }

        test(
            "given an existing READY snapshot when binder wakes with Opening mount then the durable snapshot stays READY without redraw",
        ) {
            runTest {
                val dir = createTempDirectory("widget-binder-opening-preserve").toFile()
                try {
                    val store = snapshotStore(dir, testScheduler)
                    store.write(
                        workspaceId = "ws-1",
                        projectionStamp = 12L,
                        availability = WidgetSnapshotAvailability.READY,
                        items =
                        listOf(
                            WidgetGlanceSnapshotItem(
                                id = "cached-1",
                                timestampMillis = 1_000L,
                                previewText = "cached memo body",
                            ),
                        ),
                        reviews =
                        listOf(
                            WidgetGlanceReviewItem(
                                id = "rev-1",
                                timestampMillis = 900L,
                                previewText = "cached flashback",
                                dateKey = "2026-11-10",
                            ),
                        ),
                    )
                    val widgets = RecordingWidgetRepository()
                    val ticking = TickingMemoListQueryRepository()
                    val engineReadiness = FakeEngineReadinessRepository(initial = EngineReadiness.Opening)

                    binder(ticking, widgets, store, engineReadiness = engineReadiness)
                    runCurrent()

                    val after = store.read() as WidgetGlanceSnapshot.Ready
                    after.availability shouldBe WidgetSnapshotAvailability.READY
                    after.items.map { it.id } shouldBe listOf("cached-1")
                    after.reviews.map { it.id } shouldBe listOf("rev-1")
                    widgets.updated shouldBe 0
                } finally {
                    dir.deleteRecursively()
                }
            }
        }

        test(
            "given an existing READY snapshot when session is locked during Opening mount then sensitive fields are redacted immediately",
        ) {
            runTest {
                val dir = createTempDirectory("widget-binder-opening-redact").toFile()
                try {
                    val file = storeFile(dir)
                    val store = snapshotStore(dir, testScheduler)
                    store.write(
                        workspaceId = "ws-1",
                        projectionStamp = 15L,
                        availability = WidgetSnapshotAvailability.READY,
                        items =
                        listOf(
                            WidgetGlanceSnapshotItem(
                                id = "cached-1",
                                timestampMillis = 1_000L,
                                previewText = "secret body",
                                tags = listOf("private"),
                                isPinned = true,
                            ),
                        ),
                        tasks =
                        listOf(
                            WidgetGlanceTaskItem(
                                memoId = "cached-1",
                                lineIndex = 0,
                                done = false,
                                text = "secret task",
                                dateKey = "2026-11-14",
                            ),
                        ),
                        reviews =
                        listOf(
                            WidgetGlanceReviewItem(
                                id = "rev-1",
                                timestampMillis = 900L,
                                previewText = "secret review",
                                dateKey = "2026-11-10",
                            ),
                        ),
                    )
                    val widgets = RecordingWidgetRepository()
                    val ticking = TickingMemoListQueryRepository()
                    val engineReadiness = FakeEngineReadinessRepository(initial = EngineReadiness.Opening)
                    val session = RecordingSecuritySessionPolicy(SecuritySessionState.Locked)

                    binder(ticking, widgets, store, engineReadiness = engineReadiness, session = session)
                    runCurrent()

                    val after = store.read() as WidgetGlanceSnapshot.Ready
                    after.availability shouldBe WidgetSnapshotAvailability.REDACTED
                    after.items.single().previewText shouldBe ""
                    after.items.single().tags shouldBe emptyList()
                    after.items.single().isPinned shouldBe false
                    after.tasks shouldBe emptyList()
                    after.stats shouldBe null
                    after.reviews shouldBe emptyList()
                    file.readText().contains("secret") shouldBe false
                    widgets.updated shouldBe 1
                } finally {
                    dir.deleteRecursively()
                }
            }
        }
    }

    private fun kotlinx.coroutines.test.TestScope.binder(
        ticking: TickingMemoListQueryRepository,
        widgets: RecordingWidgetRepository,
        store: WidgetGlanceSnapshotStore,
        engineReadiness: FakeEngineReadinessRepository = FakeEngineReadinessRepository(),
        session: RecordingSecuritySessionPolicy = RecordingSecuritySessionPolicy(SecuritySessionState.LockOff),
        taskRepository: MemoTaskRepository = FakeTaskRepository(),
        memoStore: FakeMemoStore = FakeMemoStore(),
        statisticsRepository: com.lomo.domain.repository.MemoStatisticsRepository =
            FakeMemoStatisticsRepository(memoStore),
        today: LocalDate = LocalDate.of(2026, 11, 14),
        debounceMillis: Long = WidgetProjectionBinder.WIDGET_PROJECTION_DEBOUNCE_MILLIS,
    ): WidgetProjectionBinder = WidgetProjectionBinder(
        scope = backgroundScope,
        sources =
        WidgetSnapshotSources(
            listQueryRepository = ticking,
            taskRepository = taskRepository,
            statisticsUseCase =
            MemoStatisticsUseCase(
                memoStatisticsRepository = statisticsRepository,
                dateSnapshotProvider = { MemoStatisticsDateSnapshot(ZoneOffset.UTC, today) },
            ),
            dailyReviewQueryUseCase = DailyReviewQueryUseCase(FakeMemoQueryRepository(memoStore)),
            appConfigRepository = FakeAppConfigRepository(),
        ),
        appWidgetRepository = widgets,
        snapshotStore = store,
        engineReadiness = engineReadiness,
        securitySession = session,
        debounceMillis = debounceMillis,
        zoneProvider = { ZoneOffset.UTC },
    )

    private class FixedStatsRepository(private val stats: com.lomo.domain.model.MemoStatistics) :
        com.lomo.domain.repository.MemoStatisticsRepository {
        override suspend fun getMemoStatistics(
            zone: java.time.ZoneId,
            today: LocalDate,
        ): com.lomo.domain.model.MemoStatistics = stats

        override fun observeMemoStatistics(
            zone: java.time.ZoneId,
            today: LocalDate,
        ): kotlinx.coroutines.flow.Flow<com.lomo.domain.model.MemoStatistics> = MutableStateFlow(stats)

        override fun getMemoCountFlow(): kotlinx.coroutines.flow.Flow<Int> = MutableStateFlow(stats.totalMemos)

        override fun getSidebarStatisticsFlow(): Flow<MemoSidebarStatistics> = MutableStateFlow(
            MemoSidebarStatistics(stats.totalMemos, stats.memoCountByDate, stats.tagCounts),
        )

        override fun getMemoCountByDateFlow(): kotlinx.coroutines.flow.Flow<Map<String, Int>> =
            MutableStateFlow(emptyMap())

        override fun getTagCountsFlow(): kotlinx.coroutines.flow.Flow<List<com.lomo.domain.model.MemoTagCount>> =
            MutableStateFlow(stats.tagCounts)

        override fun getActiveDayCount(): kotlinx.coroutines.flow.Flow<Int> = MutableStateFlow(stats.activeDays)
    }

    private class FakeTaskRepository(var tasks: List<MemoTask> = emptyList()) : MemoTaskRepository {
        override suspend fun listTasks(): List<MemoTask> = tasks

        override suspend fun toggleTask(task: MemoTask, done: Boolean) {
            tasks = tasks.map { existing ->
                if (existing.memoId == task.memoId && existing.lineIndex == task.lineIndex) {
                    existing.copy(done = done)
                } else {
                    existing
                }
            }
        }
    }

    private fun storeFile(dir: File): File = dir.resolve(WIDGET_GLANCE_SNAPSHOT_FILE_NAME)

    private fun snapshotStore(
        dir: File,
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
    ): WidgetGlanceSnapshotStore = WidgetGlanceSnapshotStore(
        dir.resolve(WIDGET_GLANCE_SNAPSHOT_FILE_NAME),
        StandardTestDispatcher(scheduler),
    )

    private fun sampleMemo(id: String, timestampMillis: Long, content: String = "content"): Memo = Memo(
        id = id,
        timestamp = timestampMillis,
        content = content,
        rawContent = content,
        dateKey = "2026-09-12",
    )

    private class TickingMemoListQueryRepository : MemoListQueryRepository {
        val publications = MutableSharedFlow<MemoProjectionPublication>(extraBufferCapacity = 8)
        var recent: List<Memo> = emptyList()
        var failOnRecent = false
        var cancelOnRecent = false

        override fun getGalleryItemsPagingSource(): PagingSource<String, com.lomo.domain.model.GalleryMemo> =
            UnusedPagingSource()

        override suspend fun getRecentMemos(limit: Int): List<Memo> {
            if (cancelOnRecent) throw CancellationException("projection query cancelled")
            if (failOnRecent) throw IllegalStateException("projection query failed")
            return recent.take(limit)
        }

        override suspend fun getMemoCount(): Int = recent.size

        override fun observeListProjection() = publications
    }

    private class UnusedPagingSource : PagingSource<String, com.lomo.domain.model.GalleryMemo>() {
        override suspend fun load(params: LoadParams<String>): LoadResult<String, com.lomo.domain.model.GalleryMemo> =
            LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)

        override fun getRefreshKey(state: PagingState<String, com.lomo.domain.model.GalleryMemo>): String? = null
    }

    private class RecordingWidgetRepository : AppWidgetRepository(mockk()) {
        var updated: Int = 0
            private set
        var onUpdate: (suspend () -> Unit)? = null

        override suspend fun updateAllWidgets() {
            onUpdate?.invoke()
            updated += 1
        }
    }

    private class RecordingSecuritySessionPolicy(initial: SecuritySessionState) : SecuritySessionPolicy {
        private val states = MutableStateFlow(initial)

        fun publish(state: SecuritySessionState) {
            states.value = state
        }

        override suspend fun authorizeCredentialRead(): CredentialReadAuthorization =
            CredentialReadAuthorization.Authorized

        override suspend fun current(): SecuritySessionState = states.value

        override fun observe(): StateFlow<SecuritySessionState> = states.asStateFlow()
    }
}
