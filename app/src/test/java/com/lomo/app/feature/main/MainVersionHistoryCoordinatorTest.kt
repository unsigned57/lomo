package com.lomo.app.feature.main

import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.MemoRevisionCursor
import com.lomo.domain.model.MemoRevisionLifecycleState
import com.lomo.domain.model.MemoRevisionOrigin
import com.lomo.domain.model.MemoRevisionPage
import com.lomo.domain.repository.MemoVersionRepository
import com.lomo.domain.usecase.LoadMemoRevisionHistoryUseCase
import com.lomo.domain.usecase.RestoreMemoRevisionUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: MainVersionHistoryCoordinator
 * - Behavior focus: history pagination state, incremental loading, and restore re-entry protection.
 * - Observable outcomes: exposed loaded state contents, has-more/loading-more/restoring flags, hidden-on-success,
 *   and suppressed duplicate restore attempts while one restore is still in flight.
 * - Red phase: Fails before the fix because the coordinator only supports one-shot loads and has no restore-in-progress guard.
 * - Excludes: Compose rendering, ViewModel wiring, and repository persistence internals.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainVersionHistoryCoordinatorTest {
    @Test
    fun `load and loadMore append pages until history is exhausted`() =
        runTest {
            val memo = testMemo(id = "memo-page")
            val repository =
                FakeMemoVersionRepository(
                    pagesByCursor =
                        linkedMapOf(
                            null to
                                MemoRevisionPage(
                                    items = listOf(testRevision(id = "r3", content = "v3"), testRevision(id = "r2", content = "v2")),
                                    nextCursor = MemoRevisionCursor(createdAt = 2L, revisionId = "r2"),
                                ),
                            MemoRevisionCursor(createdAt = 2L, revisionId = "r2") to
                                MemoRevisionPage(
                                    items = listOf(testRevision(id = "r1", content = "v1")),
                                    nextCursor = null,
                                ),
                        ),
                )
            val coordinator =
                MainVersionHistoryCoordinator(
                    loadMemoRevisionHistoryUseCase = LoadMemoRevisionHistoryUseCase(repository),
                    restoreMemoRevisionUseCase = RestoreMemoRevisionUseCase(repository),
                )

            coordinator.load(memo)
            val loadedAfterFirstPage = coordinator.state.value as MainVersionHistoryState.Loaded
            coordinator.loadMore()
            val loadedAfterSecondPage = coordinator.state.value as MainVersionHistoryState.Loaded

            assertEquals(listOf("v3", "v2"), loadedAfterFirstPage.versions.map(MemoRevision::memoContent))
            assertTrue(loadedAfterFirstPage.hasMore)
            assertFalse(loadedAfterFirstPage.isLoadingMore)

            assertEquals(listOf("v3", "v2", "v1"), loadedAfterSecondPage.versions.map(MemoRevision::memoContent))
            assertFalse(loadedAfterSecondPage.hasMore)
            assertFalse(loadedAfterSecondPage.isLoadingMore)
            assertEquals(listOf(null, MemoRevisionCursor(createdAt = 2L, revisionId = "r2")), repository.requestedCursors)
        }

    @Test
    fun `restore ignores duplicate requests while a restore is already running`() =
        runTest {
            val memo = testMemo(id = "memo-restore")
            val revision = testRevision(id = "restore-target", content = "historical")
            val gate = CompletableDeferred<Unit>()
            val repository =
                FakeMemoVersionRepository(
                    pagesByCursor =
                        linkedMapOf(
                            null to MemoRevisionPage(items = listOf(revision), nextCursor = null),
                        ),
                    restoreGate = gate,
                )
            val coordinator =
                MainVersionHistoryCoordinator(
                    loadMemoRevisionHistoryUseCase = LoadMemoRevisionHistoryUseCase(repository),
                    restoreMemoRevisionUseCase = RestoreMemoRevisionUseCase(repository),
                )
            coordinator.load(memo)

            val firstRestore = async { coordinator.restore(memo, revision) }
            runCurrent()
            val restoringState = coordinator.state.value as MainVersionHistoryState.Loaded

            val secondRestore = async { coordinator.restore(memo, revision) }
            runCurrent()

            assertTrue(restoringState.isRestoring)
            assertEquals("restore-target", restoringState.restoringRevisionId)
            assertEquals(1, repository.restoreCalls)

            gate.complete(Unit)
            firstRestore.await()
            secondRestore.await()

            assertEquals(MainVersionHistoryState.Hidden, coordinator.state.value)
        }

    @Test
    fun `restore ignores the current revision`() =
        runTest {
            val memo = testMemo(id = "memo-current")
            val currentRevision = testRevision(id = "current", content = "latest", isCurrent = true)
            val repository =
                FakeMemoVersionRepository(
                    pagesByCursor =
                        linkedMapOf(
                            null to MemoRevisionPage(items = listOf(currentRevision), nextCursor = null),
                        ),
                )
            val coordinator =
                MainVersionHistoryCoordinator(
                    loadMemoRevisionHistoryUseCase = LoadMemoRevisionHistoryUseCase(repository),
                    restoreMemoRevisionUseCase = RestoreMemoRevisionUseCase(repository),
                )

            coordinator.load(memo)
            val loadedState = coordinator.state.value

            coordinator.restore(memo, currentRevision)

            assertEquals(0, repository.restoreCalls)
            assertEquals(loadedState, coordinator.state.value)
        }
}

private class FakeMemoVersionRepository(
    private val pagesByCursor: Map<MemoRevisionCursor?, MemoRevisionPage>,
    private val restoreGate: CompletableDeferred<Unit>? = null,
) : MemoVersionRepository {
    val requestedCursors = mutableListOf<MemoRevisionCursor?>()
    var restoreCalls: Int = 0
        private set

    override suspend fun listMemoRevisions(
        memo: Memo,
        cursor: MemoRevisionCursor?,
        limit: Int,
    ): MemoRevisionPage {
        requestedCursors += cursor
        return requireNotNull(pagesByCursor[cursor]) { "Missing page for cursor=$cursor" }
    }

    override suspend fun restoreMemoRevision(
        currentMemo: Memo,
        revisionId: String,
    ) {
        restoreCalls += 1
        restoreGate?.await()
    }

    override suspend fun clearAllMemoSnapshots() = Unit
}

private fun testMemo(id: String): Memo =
    Memo(
        id = id,
        timestamp = 1L,
        updatedAt = 1L,
        content = "current",
        rawContent = "- 09:00 current",
        dateKey = "2026_03_27",
    )

private fun testRevision(
    id: String,
    content: String,
    isCurrent: Boolean = false,
): MemoRevision =
    MemoRevision(
        revisionId = id,
        parentRevisionId = null,
        memoId = "memo",
        commitId = "commit-$id",
        batchId = null,
        createdAt = id.removePrefix("r").toLongOrNull() ?: 1L,
        origin = MemoRevisionOrigin.LOCAL_EDIT,
        summary = "Edited memo",
        lifecycleState = MemoRevisionLifecycleState.ACTIVE,
        memoContent = content,
        isCurrent = isCurrent,
    )
