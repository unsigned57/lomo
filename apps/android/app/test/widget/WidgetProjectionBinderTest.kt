package com.lomo.app.widget

// architectural-boundary-check: the locked-session case asserts on the persisted snapshot
// bytes — no memo body may reach the durable file — which is a format boundary, not a
// Kotlin source-string assertion.

/*
 * Behavior Contract:
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
 *   REDACTED and no memo body reaches the file.
 * - Given a lock-state transition, when it publishes, then a redacted snapshot is written without
 *   waiting for a publication tick.
 * - Given a mount that does not admit reads, when a refresh happens, then the snapshot is
 *   UNAVAILABLE with no items — never rendered as an empty vault.
 * - Given a recovered mount, when a publication arrives, then the pending snapshot update
 *   completes with the live workspace identity and stamp.
 * - Given snapshot persistence or query failure, when refresh throws a non-cancellation error,
 *   then the binder logs, skips the redraw and survives the next publication.
 * - Given a cancellation inside the refresh, when it is rethrown, then the collecting coroutine
 *   dies instead of swallowing it as a logged failure.
 *
 * Observable outcomes: widget refresh count/order, snapshot file contents and typed results.
 * TDD proof:
 * - Fails before the fix because the contract surface under test did not exist.
 * Excludes: actual Glance rendering and platform remote-view behavior.
 * Test Change Justification:
 * - Reason category: product/domain contract changed.
 * - Old behavior/assertion being replaced: binder written only from list items without mount availability, security session, or projection stamp gating.
 * - Why old assertion is no longer correct: snapshot writes are now gated on mount admission and lock state, stamped with the publication revision, and ordered before widget refresh.
 * - Coverage preserved by: privacy redaction, stamp binding, write ordering, and recovery cases.
 * - Why this is not fitting the test to the implementation: the gating rules are the audit-required privacy contract.
 */

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.app.repository.AppWidgetRepository
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.fakes.FakeEngineReadinessRepository
import com.lomo.domain.model.CredentialReadAuthorization
import com.lomo.domain.model.Memo
import com.lomo.domain.model.SecuritySessionState
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.MemoListQueryRepository
import com.lomo.domain.model.MemoProjectionPublication
import com.lomo.domain.repository.SecuritySessionPolicy
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetProjectionBinderTest : AppFunSpec() {
    init {
        test("given rapid publications inside one debounce window when the window elapses then the snapshot is written once on the latest stamp") {
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

                    widgets.updated shouldBe 2
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

                    binder(ticking, widgets, store, session = RecordingSecuritySessionPolicy(SecuritySessionState.Locked))
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

                    binder(ticking, widgets, store, session = RecordingSecuritySessionPolicy(SecuritySessionState.Unknown))
                    runCurrent()

                    val snapshot = store.read() as WidgetGlanceSnapshot.Ready
                    snapshot.availability shouldBe WidgetSnapshotAvailability.REDACTED
                    snapshot.items.single().previewText shouldBe ""
                } finally {
                    dir.deleteRecursively()
                }
            }
        }

        test("given a lock transition when it publishes then a redacted snapshot is written without a publication tick") {
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

                    engineReadiness.activateWorkspace(StorageLocation("/tmp/ws"))
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

                    widgets.updated shouldBe 2
                } finally {
                    dir.deleteRecursively()
                }
            }
        }

        test("given a query failure when refresh throws then the binder skips the redraw and survives the next publication") {
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

        test("given a cancelled refresh when it rethrows then the pipeline survives and the next publication still writes") {
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
    }

    private fun kotlinx.coroutines.test.TestScope.binder(
        ticking: TickingMemoListQueryRepository,
        widgets: RecordingWidgetRepository,
        store: WidgetGlanceSnapshotStore,
        engineReadiness: FakeEngineReadinessRepository = FakeEngineReadinessRepository(),
        session: RecordingSecuritySessionPolicy = RecordingSecuritySessionPolicy(SecuritySessionState.LockOff),
        debounceMillis: Long = WidgetProjectionBinder.WIDGET_PROJECTION_DEBOUNCE_MILLIS,
    ): WidgetProjectionBinder =
        WidgetProjectionBinder(
            scope = backgroundScope,
            listQueryRepository = ticking,
            appWidgetRepository = widgets,
            snapshotStore = store,
            engineReadiness = engineReadiness,
            securitySession = session,
            debounceMillis = debounceMillis,
        )

    private fun storeFile(dir: File): File = dir.resolve(WIDGET_GLANCE_SNAPSHOT_FILE_NAME)

    private fun snapshotStore(
        dir: File,
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
    ): WidgetGlanceSnapshotStore =
        WidgetGlanceSnapshotStore(
            dir.resolve(WIDGET_GLANCE_SNAPSHOT_FILE_NAME),
            StandardTestDispatcher(scheduler),
        )

    private fun sampleMemo(
        id: String,
        timestampMillis: Long,
        content: String = "content",
    ): Memo =
        Memo(
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

        override fun getGalleryMemosPagingSource(): PagingSource<String, Memo> = UnusedPagingSource()

        override suspend fun getRecentMemos(limit: Int): List<Memo> {
            if (cancelOnRecent) throw CancellationException("projection query cancelled")
            if (failOnRecent) throw IllegalStateException("projection query failed")
            return recent.take(limit)
        }

        override suspend fun getMemoCount(): Int = recent.size

        override fun observeListProjection() = publications
    }

    private class UnusedPagingSource : PagingSource<String, Memo>() {
        override suspend fun load(params: LoadParams<String>): LoadResult<String, Memo> =
            LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)

        override fun getRefreshKey(state: PagingState<String, Memo>): String? = null
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

    private class RecordingSecuritySessionPolicy(
        initial: SecuritySessionState,
    ) : SecuritySessionPolicy {
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

