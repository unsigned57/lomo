package com.lomo.data.engine.store

/*
 * Behavior Contract:
 * - Unit under test: PublishingStorePort.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: the store write adapter is the single publication exit for owner stamps;
 *   repositories must not call StoreInvalidationBus.publish themselves.
 *
 * Scenarios:
 * - Given a returned memo commit, when applyMemoCommand runs, then the bus publishes that stamp
 *   and registered paging sources invalidate.
 * - Given a create accepted mid-flight then durably committed, when applyMemoCommand runs, then
 *   paging invalidates on the acceptance stamp and again on the durable commit: the committed row
 *   is no longer pending and every cursor minted at the acceptance revision is stale.
 * - Given an accepted create that is withdrawn, when applyMemoCommand fails, then the withdrawal
 *   stamp publishes so the pending row disappears, and the failure still reaches the caller.
 * - Given a rewritten rebuild, when startRebuild runs, then the high-water revision publishes.
 * - Given a rebuild that did not rewrite, when startRebuild runs, then the publication clock is
 *   unchanged.
 * - Given a batch delete commit, when permanentDeleteMany runs, then the batch stamp publishes.
 *
 * Observable outcomes:
 * - StoreProjectionPublication revision/sequence and PagingSource invalid state.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=data --include-classes='com.lomo.data.engine.store.PublishingStorePortTest'
 * - RED: PublishingStorePort did not exist, so each repository still called bus.publish itself.
 *
 * Excludes:
 * - JNI, Compose, and repository reminder/media side effects.
 * Test Change Justification:
 * - Reason category: pending creates now publish through the session path.
 * - Old behavior/assertion being replaced: a durable commit after a mid-flight pending publish
 *   only advanced the clock (`bus.confirm`) and left paging sources valid.
 * - Why old assertion is no longer correct: the committed row differs from the pending one
 *   (`isPending`, revision, fingerprint) and page cursors embed the acceptance revision, so a
 *   source that skips the commit keeps a pending badge and stale cursors.
 * - Coverage preserved by: both stamps still publish in order; the withdrawal path is new.
 * - Why this is not fitting the test to the implementation: the visible row state is the contract.
 * - Old behavior evidence: StoreInvalidationBusTest pinned `bus.confirm` leaving a source
 *   registered after the pending stamp valid; the regenerated FFI no longer compiles the old path.
 * - Reason category: dead production surface removed.
 * - Old behavior/assertion being replaced: fake overrides for retired StorePort read methods.
 * - Why old assertion is no longer correct: the port interface no longer declares those members.
 * - Coverage preserved by: remaining publish-path assertions on the live port surface.
 * - Why this is not fitting the test to the implementation: it only deletes overrides of deleted interface members.
 */

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.data.repository.StoreInvalidationBus
import com.lomo.data.repository.StoreProjectionObserver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PublishingStorePortTest :
    FunSpec({
        test("applyMemoCommand publishes the returned commit and invalidates paging") {
            val bus = StoreInvalidationBus()
            val paging = ProjectionPagingSource()
            bus.register(paging)
            val commit = sampleCommit(revision = 4L, sequence = 7L)
            val port = PublishingStorePort(RecordingWritePort(commit = commit), StoreProjectionObserver(bus))

            port.applyMemoCommand(sampleCommand(), onPublication = {}) shouldBe commit

            paging.invalid shouldBe true
            bus.publications.value.coreRevision shouldBe 4L
            bus.publications.value.eventSequence shouldBe 7L
        }

        test("an accepted create invalidates paging on acceptance and again on its durable commit") {
            val bus = StoreInvalidationBus()
            val pendingSource = ProjectionPagingSource()
            bus.register(pendingSource)
            val pending = sampleCommit(revision = 1L, sequence = 1L)
            val durable = sampleCommit(revision = 2L, sequence = 2L)
            val recordedAfterAcceptance = mutableListOf<ProjectionPagingSource>()
            val port =
                PublishingStorePort(
                    RecordingWritePort(
                        commit = durable,
                        pending = pending,
                        afterPending = {
                            recordedAfterAcceptance += ProjectionPagingSource().also(bus::register)
                        },
                    ),
                    StoreProjectionObserver(bus),
                )

            port.applyMemoCommand(sampleCommand(), onPublication = {})

            pendingSource.invalid shouldBe true
            // A source registered after the acceptance (the reload that shows the pending row) must
            // be invalidated by the durable commit.
            recordedAfterAcceptance.single().invalid shouldBe true
            bus.publications.value.coreRevision shouldBe 2L
            bus.publications.value.eventSequence shouldBe 2L
        }

        test(
            "given a withdrawal when create fails then pending-row paging invalidates and failure reaches the caller",
        ) {
            val bus = StoreInvalidationBus()
            val afterAcceptance = ProjectionPagingSource()
            val withdrawn = sampleCommit(revision = 2L, sequence = 2L)
            val port = PublishingStorePort(
                RecordingWritePort(
                    pending = sampleCommit(),
                    afterPending = { bus.register(afterAcceptance) },
                    withdrawal = withdrawn,
                ),
                StoreProjectionObserver(bus),
            )
            val publications = mutableListOf<StoreMemoCommandPublication>()

            shouldThrow<IllegalStateException> {
                port.applyMemoCommand(sampleCommand(), publications::add)
            }.message shouldBe "durable write failed"

            publications.map { it.commit.coreRevision } shouldBe listOf(1L, 2L)
            publications.last() shouldBe StoreMemoCommandPublication.Withdrawn(withdrawn)
            afterAcceptance.invalid shouldBe true
            bus.publications.value.coreRevision shouldBe 2L
        }

        test("startRebuild publishes only when the projection was rewritten") {
            val bus = StoreInvalidationBus()
            val rewritten =
                PublishingStorePort(
                    RecordingWritePort(rebuild = sampleRebuild(highWater = 9L, rewritten = true)),
                    StoreProjectionObserver(bus),
                )
            rewritten.startRebuild(64)
            bus.publications.value.coreRevision shouldBe 9L

            val unchangedBus = StoreInvalidationBus()
            val skipped =
                PublishingStorePort(
                    RecordingWritePort(rebuild = sampleRebuild(highWater = 9L, rewritten = false)),
                    StoreProjectionObserver(unchangedBus),
                )
            skipped.startRebuild(64)
            unchangedBus.publications.value.coreRevision shouldBe 0L
        }

        test("permanentDeleteMany publishes the batch stamp") {
            val bus = StoreInvalidationBus()
            val paging = ProjectionPagingSource()
            bus.register(paging)
            val batch =
                StoreMemoBatchCommit(
                    operationId = "clear-1",
                    deleted = listOf(StoreMemoDeletedMemo("memo-1", emptyList())),
                    coreRevision = 5L,
                    eventSequence = 8L,
                    scopes = listOf(StoreInvalidationScope.Trash),
                    idempotentReplay = false,
                )
            val port = PublishingStorePort(RecordingWritePort(batch = batch), StoreProjectionObserver(bus))

            port.permanentDeleteMany("clear-1", listOf(sampleDeleteTarget()))

            paging.invalid shouldBe true
            bus.publications.value.coreRevision shouldBe 5L
            bus.publications.value.eventSequence shouldBe 8L
        }
    })

private class ProjectionPagingSource : PagingSource<Int, String>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, String> =
        LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)

    override fun getRefreshKey(state: PagingState<Int, String>): Int? = null
}

private class RecordingWritePort(
    private val commit: StoreMemoCommit = sampleCommit(),
    private val pending: StoreMemoCommit? = null,
    private val afterPending: () -> Unit = {},
    private val withdrawal: StoreMemoCommit? = null,
    private val batch: StoreMemoBatchCommit? = null,
    private val rebuild: StoreRebuildResult = sampleRebuild(),
) : StorePort {
    override fun queryMemos(
        query: StoreMemoQuery,
        cursor: StorePageCursor?,
        pageSize: Int,
        startMemoId: String?,
        backward: Boolean,
    ): StoreMemoPage = error("read not expected")

    override fun getMemo(memoId: String): StoreMemoSnapshot? = error("read not expected")

    override fun queryCount(query: StoreMemoQuery): Long = error("read not expected")

    override fun sidebarProjection(): StoreSidebarProjection = error("read not expected")

    override fun queryReminderPlan(nowUtcMs: Long): StoreReminderPlan = error("read not expected")

    override fun applyMemoCommand(
        command: StoreMemoCommand,
        onPublication: (com.lomo.data.engine.store.StoreMemoCommandPublication) -> Unit,
    ): StoreMemoCommit {
        pending?.let { accepted ->
            onPublication(com.lomo.data.engine.store.StoreMemoCommandPublication.Accepted(accepted))
            afterPending()
        }
        withdrawal?.let {
            onPublication(StoreMemoCommandPublication.Withdrawn(it))
            error("durable write failed")
        }
        return commit
    }

    override fun permanentDeleteMany(operationId: String, targets: List<StoreMemoDeleteTarget>): StoreMemoBatchCommit =
        batch ?: error("batch not expected")

    override fun startRebuild(batchSize: Int): StoreRebuildResult = rebuild

    override fun snoozeReminder(opaqueId: String, interval: com.lomo.domain.model.ReminderInterval) =
        error("reminder snooze is not expected")

    override fun recoverReminderSnooze() = error("reminder snooze recovery is not expected")
}

private fun sampleCommand(): StoreMemoCommand = StoreMemoCommand(
    operationId = "op-1",
    kind = StoreMemoCommandKind.Create,
    memoId = "",
    expectedRevision = 0L,
    content = "body",
)

private fun sampleCommit(revision: Long = 1L, sequence: Long = 1L): StoreMemoCommit = StoreMemoCommit(
    operationId = "op-1",
    memoId = "memo-1",
    coreRevision = revision,
    eventSequence = sequence,
    contentRevision = revision,
    fileFingerprint = "fp",
    scopes = listOf(StoreInvalidationScope.MemoList),
    idempotentReplay = false,
)

private fun sampleRebuild(highWater: Long = 1L, rewritten: Boolean = true): StoreRebuildResult = StoreRebuildResult(
    memosIndexed = 1L,
    fileCount = 1L,
    attachmentCount = 0L,
    workspaceDigest = "ws",
    storeDigest = "st",
    corruptLomoIsolated = 0L,
    highWaterRevision = highWater,
    rewritten = rewritten,
)

private fun sampleDeleteTarget(): StoreMemoDeleteTarget = StoreMemoDeleteTarget(
    memoId = "memo-1",
    sourcePath = "2026-09-20.md",
    expectedRevision = 1L,
    expectedFingerprint = "fp",
)
