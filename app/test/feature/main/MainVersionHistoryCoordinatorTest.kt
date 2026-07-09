package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.MemoRevisionCursor
import com.lomo.domain.model.MemoRevisionLifecycleState
import com.lomo.domain.model.MemoRevisionOrigin
import com.lomo.domain.model.MemoRevisionPage
import com.lomo.domain.repository.MemoMutationRepository
import com.lomo.domain.repository.MemoVersionRepository
import com.lomo.domain.usecase.LoadMemoRevisionHistoryUseCase
import com.lomo.domain.usecase.RestoreMemoRevisionUseCase
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: MainVersionHistoryCoordinator.
 * - Owning layer: app/main version history orchestration.
 * - Priority tier: P1.
 * - Capability: load paged version history from the version repository while routing restore commands through
 *   the mutation lifecycle boundary.
 *
 * Scenarios:
 * - Given multiple history pages, when loadMore is requested, then loaded state appends pages until exhausted.
 * - Given a restore is already running, when restore is requested again, then only one mutation restore command runs.
 * - Given the current revision is selected, when restore is requested, then no mutation restore command runs.
 *
 * Observable outcomes:
 * - loaded version rows, has-more/loading-more/restoring flags, hidden-on-success state, requested cursors,
 *   and recorded mutation restore calls.
 *
 * TDD proof:
 * - RED: app test compilation fails until RestoreMemoRevisionUseCase is wired to MemoMutationRepository
 *   instead of the query-only MemoVersionRepository.
 *
 * Excludes:
 * - Compose rendering, ViewModel wiring, repository persistence internals, and data-layer restore execution.
 *
 * Test Change Justification:
 * - Reason category: domain contract return type extension.
 * - Old behavior/assertion being replaced: fake MemoMutationRepository.saveMemo returned Unit.
 * - Why old assertion is no longer correct: saveMemo now returns the saved Memo so callers can deep link to it.
 * - Coverage preserved by: version history restore scenarios and mutation recording remain unchanged.
 * - Why this is not fitting the test to the implementation: the fake now satisfies the domain contract while preserving observable version-history behavior.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainVersionHistoryCoordinatorTest : AppFunSpec() {
    init {
        test("load and loadMore append pages until history is exhausted") {
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
                val mutationRepository = FakeMemoMutationRepository()
                val coordinator =
                    MainVersionHistoryCoordinator(
                        loadMemoRevisionHistoryUseCase = LoadMemoRevisionHistoryUseCase(repository),
                        restoreMemoRevisionUseCase = RestoreMemoRevisionUseCase(mutationRepository),
                    )

                coordinator.load(memo)
                val loadedAfterFirstPage = coordinator.state.value as MainVersionHistoryState.Loaded
                coordinator.loadMore()
                val loadedAfterSecondPage = coordinator.state.value as MainVersionHistoryState.Loaded

                (loadedAfterFirstPage.versions.map(MemoRevision::memoContent)) shouldBe (listOf("v3", "v2"))
                ((loadedAfterFirstPage.hasMore)) shouldBe true
                ((loadedAfterFirstPage.isLoadingMore)) shouldBe false

                (loadedAfterSecondPage.versions.map(MemoRevision::memoContent)) shouldBe (listOf("v3", "v2", "v1"))
                ((loadedAfterSecondPage.hasMore)) shouldBe false
                ((loadedAfterSecondPage.isLoadingMore)) shouldBe false
                (repository.requestedCursors) shouldBe (listOf(null, MemoRevisionCursor(createdAt = 2L, revisionId = "r2")))
            }
        }

        test("restore ignores duplicate requests while a restore is already running") {
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
                    )
                val mutationRepository = FakeMemoMutationRepository(restoreGate = gate)
                val coordinator =
                    MainVersionHistoryCoordinator(
                        loadMemoRevisionHistoryUseCase = LoadMemoRevisionHistoryUseCase(repository),
                        restoreMemoRevisionUseCase = RestoreMemoRevisionUseCase(mutationRepository),
                    )
                coordinator.load(memo)

                val firstRestore = async { coordinator.restore(memo, revision) }
                runCurrent()
                val restoringState = coordinator.state.value as MainVersionHistoryState.Loaded

                val secondRestore = async { coordinator.restore(memo, revision) }
                runCurrent()

                ((restoringState.isRestoring)) shouldBe true
                (restoringState.restoringRevisionId) shouldBe ("restore-target")
                (mutationRepository.restoreCalls) shouldBe (1)

                gate.complete(Unit)
                firstRestore.await()
                secondRestore.await()

                (coordinator.state.value) shouldBe (MainVersionHistoryState.Hidden)
            }
        }

        test("restore ignores the current revision") {
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
                val mutationRepository = FakeMemoMutationRepository()
                val coordinator =
                    MainVersionHistoryCoordinator(
                        loadMemoRevisionHistoryUseCase = LoadMemoRevisionHistoryUseCase(repository),
                        restoreMemoRevisionUseCase = RestoreMemoRevisionUseCase(mutationRepository),
                    )

                coordinator.load(memo)
                val loadedState = coordinator.state.value

                coordinator.restore(memo, currentRevision)

                (mutationRepository.restoreCalls) shouldBe (0)
                (coordinator.state.value) shouldBe (loadedState)
            }
        }
    }

}

private class FakeMemoVersionRepository(
    private val pagesByCursor: Map<MemoRevisionCursor?, MemoRevisionPage>,
) : MemoVersionRepository {
    val requestedCursors = mutableListOf<MemoRevisionCursor?>()

    override suspend fun listMemoRevisions(
        memo: Memo,
        cursor: MemoRevisionCursor?,
        limit: Int,
    ): MemoRevisionPage {
        requestedCursors += cursor
        return requireNotNull(pagesByCursor[cursor]) { "Missing page for cursor=$cursor" }
    }

    override suspend fun clearAllMemoSnapshots() = Unit
}

private class FakeMemoMutationRepository(
    private val restoreGate: CompletableDeferred<Unit>? = null,
) : MemoMutationRepository {
    var restoreCalls: Int = 0
        private set

    override suspend fun refreshMemos() = Unit

    override suspend fun saveMemo(
        content: String,
        timestamp: Long,
        geoLocation: String?,
    ): Memo = Memo(
        id = timestamp.toString(),
        timestamp = timestamp,
        content = content,
        rawContent = content,
        dateKey = "test",
    )

    override suspend fun updateMemo(
        memo: Memo,
        newContent: String,
    ) = Unit

    override suspend fun deleteMemo(memo: Memo) = Unit

    override suspend fun restoreMemoRevision(
        currentMemo: Memo,
        revisionId: String,
    ) {
        restoreCalls += 1
        restoreGate?.await()
    }

    override suspend fun setMemoPinned(
        memoId: String,
        pinned: Boolean,
    ) = Unit
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
