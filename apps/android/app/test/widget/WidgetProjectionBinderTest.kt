package com.lomo.app.widget

/*
 * Behavior Contract:
 * - Unit under test: WidgetProjectionBinder
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: refresh Glance widgets from list-projection publications instead of mutation-site
 *   commands, and persist the Glance snapshot those widgets read.
 *
 * Scenarios:
 * - Given a list-projection tick, when the debounce window elapses, then widgets update once.
 * - Given several ticks inside the debounce window, when the window elapses, then widgets update
 *   once.
 * - Given recent memos on a projection tick, when the debounce window elapses, then the Glance
 *   snapshot is persisted before widgets update.
 *
 * Observable outcomes:
 * - AppWidgetRepository.updateAllWidgets call count after virtual time advances, and snapshot
 *   file contents.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=app --include-classes='com.lomo.app.widget.WidgetProjectionBinderTest'
 * - RED before the binder existed because widget refresh was commanded from editor/delete paths.
 * - RED on 2026-09-12 because the binder updated Glance without writing a store-free snapshot.
 *
 * Excludes:
 * - Glance layout, JNI, and widget process isolation wiring.
 */

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.app.repository.AppWidgetRepository
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoListQueryRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetProjectionBinderTest : FunSpec({
    test("a projection tick updates widgets after the debounce window") {
        runTest {
            val widgets = CountingAppWidgetRepository()
            val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            val directory = createTempDirectory("widget-binder-one-tick").toFile()
            try {
                WidgetProjectionBinder(
                    scope = backgroundScope,
                    listQueryRepository = TickingMemoListQueryRepository(ticks),
                    appWidgetRepository = widgets,
                    snapshotStore =
                        WidgetGlanceSnapshotStore(directory.resolve(WIDGET_GLANCE_SNAPSHOT_FILE_NAME), kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)),
                )
                runCurrent()

                ticks.emit(Unit)
                runCurrent()
                advanceTimeBy(WidgetProjectionBinder.WIDGET_PROJECTION_DEBOUNCE_MILLIS - 1)
                widgets.updateAllWidgetsCalledCount shouldBe 0

                advanceTimeBy(1)
                runCurrent()
                widgets.updateAllWidgetsCalledCount shouldBe 1
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    test("ticks inside the debounce window coalesce to one widget update") {
        runTest {
            val widgets = CountingAppWidgetRepository()
            val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
            val directory = createTempDirectory("widget-binder-coalesce").toFile()
            try {
                WidgetProjectionBinder(
                    scope = backgroundScope,
                    listQueryRepository = TickingMemoListQueryRepository(ticks),
                    appWidgetRepository = widgets,
                    snapshotStore =
                        WidgetGlanceSnapshotStore(directory.resolve(WIDGET_GLANCE_SNAPSHOT_FILE_NAME), kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)),
                )
                runCurrent()

                ticks.emit(Unit)
                ticks.emit(Unit)
                ticks.emit(Unit)
                runCurrent()
                advanceTimeBy(WidgetProjectionBinder.WIDGET_PROJECTION_DEBOUNCE_MILLIS)
                runCurrent()

                widgets.updateAllWidgetsCalledCount shouldBe 1
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    test("a projection tick persists the glance snapshot before widgets update") {
        runTest {
            val widgets = CountingAppWidgetRepository()
            val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            val directory = createTempDirectory("widget-binder-snapshot").toFile()
            try {
                val snapshotFile = directory.resolve(WIDGET_GLANCE_SNAPSHOT_FILE_NAME)
                val store = WidgetGlanceSnapshotStore(snapshotFile, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
                val memos =
                    listOf(
                        Memo(
                            id = "memo-1",
                            timestamp = 1_000L,
                            content = "snapshot body",
                            rawContent = "snapshot body",
                            dateKey = "2026-09-12",
                        ),
                    )
                WidgetProjectionBinder(
                    scope = backgroundScope,
                    listQueryRepository = TickingMemoListQueryRepository(ticks, memos),
                    appWidgetRepository = widgets,
                    snapshotStore = store,
                    nowMillis = { 5_000L },
                )
                runCurrent()

                ticks.emit(Unit)
                runCurrent()
                advanceTimeBy(WidgetProjectionBinder.WIDGET_PROJECTION_DEBOUNCE_MILLIS)
                runCurrent()

                store.read() shouldBe
                    listOf(
                        WidgetGlanceSnapshotItem(
                            id = "memo-1",
                            timestampMillis = 1_000L,
                            previewText = "snapshot body",
                        ),
                    )
                widgets.updateAllWidgetsCalledCount shouldBe 1
            } finally {
                directory.deleteRecursively()
            }
        }
    }
})

private class CountingAppWidgetRepository : AppWidgetRepository(mockk()) {
    var updateAllWidgetsCalledCount = 0
        private set

    override suspend fun updateAllWidgets() {
        updateAllWidgetsCalledCount += 1
    }
}

private class TickingMemoListQueryRepository(
    private val ticks: Flow<Unit>,
    private val recent: List<Memo> = emptyList(),
) : MemoListQueryRepository {
    override fun getGalleryMemosPagingSource(): PagingSource<String, Memo> = UnusedPagingSource()

    override suspend fun getRecentMemos(limit: Int): List<Memo> = recent.take(limit)

    override suspend fun getMemoCount(): Int = 0

    override fun observeListProjection(): Flow<Unit> = ticks
}

private class UnusedPagingSource : PagingSource<String, Memo>() {
    override suspend fun load(params: LoadParams<String>): LoadResult<String, Memo> =
        LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)

    override fun getRefreshKey(state: PagingState<String, Memo>): String? = null
}
