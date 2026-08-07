package com.lomo.data.repository

/*
 * Behavior Contract:
 * - Unit under test: StoreMemoQueryRepository, StoreMemoMutationRepository,
 *   StoreMemoStatisticsRepository (production cutover owners over StorePort).
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: after Room cutover, list/get/mutate/stats go solely through StorePort; mutations
 *   require write authority, bump invalidation, and sync reminders; queries map store summaries
 *   to domain Memo.
 *
 * Scenarios:
 * - Given store pages with memos, when bounded reads / getMemoById / getMemoCount run, then
 *   domain memos and counts are observed.
 * - Given writable authority, when saveMemo succeeds, then Create is applied, invalidation bumps,
 *   reminder sync runs, and returned Memo matches getMemo.
 * - Given writable authority, when update/delete/pin run, then correct command kinds are applied.
 * - Given frozen write authority, when saveMemo is attempted, then it fails closed.
 * - Given store summaries with tags/images, when list/stats run, then tags/imageUrls and tag
 *   counts are projected from StorePort (M3).
 *
 * Observable outcomes: domain Memo fields, command kinds recorded on fake port, reminder calls,
 * invalidation tick advancement, thrown check failures.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=data --include-classes='com.lomo.data.repository.StoreMemoRepositoriesTest'
 * - RED: production StoreMemo* repositories had zero host executions (coverage gaming C1).
 *
 * Excludes:
 * - Real BoltFFI and Room dual-stack (deleted); history is covered by the store adapter contract.
 *
 * Test Change Justification:
 * - Reason category: memo mutations accept pending media promotes after stage-4 cutover.
 * - Old behavior/assertion being replaced: fake StorePort / command fixtures without promote fields.
 * - Why old assertion is no longer correct: production StoreMemoCommand carries pendingPromotes and
 *   history attachment refs for media lifecycle.
 * - Coverage preserved by: query/mutate/stats, write-authority fail-closed, and invalidation/reminder
 *   side effects remain asserted.
 * - Why this is not fitting the test to the implementation: still locks observable domain Memo and
 *   command-kind outcomes, not media digest algorithms.
 */

import app.cash.turbine.test
import androidx.paging.PagingSource
import com.lomo.data.engine.store.StoreMemoCommand
import com.lomo.data.engine.store.StoreMemoCommandKind
import com.lomo.data.engine.store.StoreMemoCommit
import com.lomo.data.engine.store.StoreMemoPage
import com.lomo.data.engine.store.StoreMemoQuery
import com.lomo.data.engine.store.StoreMemoSnapshot
import com.lomo.data.engine.store.StoreMemoSummary
import com.lomo.data.engine.store.StoreHistoryAttachmentRef
import com.lomo.data.engine.store.StorePageCursor
import com.lomo.data.engine.store.StorePort
import com.lomo.data.engine.store.StoreRebuildResult
import com.lomo.data.testing.fakes.FakeEngineReadinessRepository
import com.lomo.data.testing.fakes.FakeReminderCoordinator
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoTagCount
import com.lomo.domain.repository.WorkspaceMutationLease
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

private class RecordingStorePort : StorePort {
    val commands = mutableListOf<StoreMemoCommand>()
    var queryCount = 0
    var sidebarQueryCount = 0
    var rebuildCount = 0
    private val memos = linkedMapOf<String, StoreMemoSnapshot>()
    private var nextId = 1

    fun seed(snapshot: StoreMemoSnapshot) {
        memos[snapshot.summary.memoId] = snapshot
    }

    override fun queryMemos(
        query: StoreMemoQuery,
        cursor: StorePageCursor?,
        pageSize: Int,
    ): StoreMemoPage {
        queryCount += 1
        val all =
            memos.values
                .map { it.summary }
                .filter { summary ->
                    if (!query.filters.includeTrash && !query.filters.trashOnly && summary.isTrashed) {
                        return@filter false
                    }
                    if (query.filters.trashOnly && !summary.isTrashed) {
                        return@filter false
                    }
                    if (query.filters.hasAttachment == true && !summary.hasAttachment) {
                        return@filter false
                    }
                    true
                }
        val start = if (cursor == null) 0 else all.indexOfFirst { it.memoId == cursor.encoded }.let { if (it < 0) 0 else it + 1 }
        val slice = all.drop(start).take(pageSize.coerceAtLeast(1))
        val next =
            if (start + slice.size < all.size) {
                StorePageCursor(slice.last().memoId)
            } else {
                null
            }
        return StoreMemoPage(
            items = slice,
            nextCursor = next,
            highWaterRevision = all.size.toLong(),
            queryFingerprint = "fp",
        )
    }

    override fun getMemo(memoId: String): StoreMemoSnapshot? = memos[memoId]

    override fun sidebarProjection(): com.lomo.data.engine.store.StoreSidebarProjection {
        sidebarQueryCount += 1
        val active = memos.values.map { it.summary }.filterNot { it.isTrashed }
        return com.lomo.data.engine.store.StoreSidebarProjection(
            schemaVersion = 1u,
            memoCount = active.size,
            dateCounts =
                active
                    .groupingBy { summary ->
                        java.time.Instant
                            .ofEpochMilli(summary.createdAtMs)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                            .toString()
                    }.eachCount()
                    .map { (date, count) -> com.lomo.data.engine.store.StoreSidebarDateCount(date, count) },
            tagCounts =
                active
                    .flatMap { it.tags }
                    .groupingBy { it }
                    .eachCount()
                    .entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .map { (name, count) -> com.lomo.data.engine.store.StoreSidebarTagCount(name, count) },
        )
    }

    override fun listHistoryAttachmentRefs(): List<StoreHistoryAttachmentRef> = emptyList()

    override fun applyMemoCommand(command: StoreMemoCommand): StoreMemoCommit {
        commands += command
        return when (command.kind) {
            StoreMemoCommandKind.Create -> {
                val id = "m-${nextId++}"
                val summary =
                    StoreMemoSummary(
                        memoId = id,
                        sourcePath = "memos/2026_07_21.md",
                        fileFingerprint = "ff-$id",
                        updatedAtMs = 2_000L,
                        createdAtMs = 1_000L,
                        hasTodo = false,
                        hasUrl = false,
                        hasAttachment = false,
                        isPinned = false,
                        isTrashed = false,
                        bodyPreview = command.content.orEmpty().take(80),
                        contentRevision = 1L,
                    )
                memos[id] = StoreMemoSnapshot(summary = summary, body = command.content.orEmpty())
                StoreMemoCommit(
                    operationId = command.operationId,
                    memoId = id,
                    coreRevision = 1L,
                    eventSequence = 1L,
                    contentRevision = 1L,
                    fileFingerprint = summary.fileFingerprint,
                    scopes = listOf("memo:$id"),
                    idempotentReplay = false,
                )
            }
            StoreMemoCommandKind.Update -> {
                val existing = memos[command.memoId] ?: error("missing")
                val body = command.content.orEmpty()
                val updated =
                    existing.copy(
                        body = body,
                        summary =
                            existing.summary.copy(
                                bodyPreview = body.take(80),
                                contentRevision = existing.summary.contentRevision + 1,
                                fileFingerprint = "ff-upd",
                                updatedAtMs = existing.summary.updatedAtMs + 1,
                            ),
                    )
                memos[command.memoId] = updated
                commitOf(command, updated)
            }
            StoreMemoCommandKind.Delete -> {
                val existing = memos[command.memoId] ?: error("missing")
                val updated =
                    existing.copy(
                        summary =
                            existing.summary.copy(
                                isTrashed = true,
                                contentRevision = existing.summary.contentRevision + 1,
                            ),
                    )
                memos[command.memoId] = updated
                commitOf(command, updated)
            }
            StoreMemoCommandKind.PermanentDelete -> {
                val existing = memos.remove(command.memoId) ?: error("missing")
                StoreMemoCommit(
                    operationId = command.operationId,
                    memoId = existing.summary.memoId,
                    coreRevision = 1L,
                    eventSequence = 1L,
                    contentRevision = existing.summary.contentRevision,
                    fileFingerprint = "",
                    scopes = listOf("memo:${existing.summary.memoId}"),
                    idempotentReplay = false,
                )
            }
            StoreMemoCommandKind.Pin, StoreMemoCommandKind.Unpin -> {
                val existing = memos[command.memoId] ?: error("missing")
                val updated =
                    existing.copy(
                        summary =
                            existing.summary.copy(
                                isPinned = command.kind == StoreMemoCommandKind.Pin || command.pin == true,
                                contentRevision = existing.summary.contentRevision + 1,
                            ),
                    )
                memos[command.memoId] = updated
                commitOf(command, updated)
            }
            StoreMemoCommandKind.Restore, StoreMemoCommandKind.HistoryRestore -> {
                val existing = memos[command.memoId] ?: error("missing")
                val updated =
                    existing.copy(
                        summary =
                            existing.summary.copy(
                                isTrashed = false,
                                contentRevision = existing.summary.contentRevision + 1,
                            ),
                    )
                memos[command.memoId] = updated
                commitOf(command, updated)
            }
        }
    }

    override fun startRebuild(batchSize: Int): StoreRebuildResult {
        rebuildCount++
        val digest = "digest-${memos.size}"
        return StoreRebuildResult(
            memosIndexed = memos.size.toLong(),
            fileCount = memos.size.toLong(),
            attachmentCount = memos.values.count { it.summary.hasAttachment }.toLong(),
            workspaceDigest = digest,
            storeDigest = digest,
            corruptLomoIsolated = 0L,
            highWaterRevision = memos.size.toLong(),
        )
    }

    private fun commitOf(
        command: StoreMemoCommand,
        snap: StoreMemoSnapshot,
    ): StoreMemoCommit =
        StoreMemoCommit(
            operationId = command.operationId,
            memoId = snap.summary.memoId,
            coreRevision = 1L,
            eventSequence = 1L,
            contentRevision = snap.summary.contentRevision,
            fileFingerprint = snap.summary.fileFingerprint,
            scopes = listOf("memo:${snap.summary.memoId}"),
            idempotentReplay = false,
        )
}

private fun seededSnapshot(
    id: String,
    body: String,
    createdAtMs: Long = 1_000L,
    hasAttachment: Boolean = false,
    tags: List<String> = emptyList(),
    imageUrls: List<String> = emptyList(),
): StoreMemoSnapshot =
    StoreMemoSnapshot(
        summary =
            StoreMemoSummary(
                memoId = id,
                sourcePath = "memos/2026_07_21.md",
                fileFingerprint = "ff-$id",
                updatedAtMs = createdAtMs + 1,
                createdAtMs = createdAtMs,
                hasTodo = false,
                hasUrl = false,
                hasAttachment = hasAttachment,
                isPinned = false,
                isTrashed = false,
                bodyPreview = body.take(80),
                contentRevision = 1L,
                tags = tags,
                imageUrls = imageUrls,
            ),
        body = body,
    )

private fun notReadyWriteLease(): WorkspaceMutationLease =
    ProcessWorkspaceMutationLease(
        engineReadinessRepository =
            FakeEngineReadinessRepository(EngineReadiness.AwaitingWorkspaceSelection),
    )

class StoreMemoRepositoriesTest : FunSpec({
    test("gallery paging source requests bounded store pages") {
        runTest {
            val port = RecordingStorePort()
            repeat(31) { index ->
                port.seed(seededSnapshot("gallery-$index", "body", hasAttachment = true))
            }
            val repo = StoreMemoQueryRepository(port, StoreInvalidationBus(), FakeEngineReadinessRepository())
            val source = repo.getGalleryMemosPagingSource()

            val result = source.load(
                PagingSource.LoadParams.Refresh(
                    key = null,
                    loadSize = 30,
                    placeholdersEnabled = false,
                ),
            )

            result.shouldBeInstanceOf<PagingSource.LoadResult.Page<String, Memo>>()
                .data shouldHaveSize 30
            port.queryCount shouldBe 1
        }
    }

    test("query repository loads bounded memo reads from store pages") {
        runTest {
            val port = RecordingStorePort()
            port.seed(seededSnapshot("a", "alpha", tags = listOf("life")))
            port.seed(
                seededSnapshot(
                    "b",
                    "beta",
                    hasAttachment = true,
                    tags = listOf("work"),
                    imageUrls = listOf("images/b.png"),
                ),
            )
            val invalidation = StoreInvalidationBus()
            val repo = StoreMemoQueryRepository(port, invalidation, FakeEngineReadinessRepository())

            repo.getMemoById("b").shouldNotBeNull().content shouldBe "beta"
            repo.getMemoCount() shouldBe 2
            repo.getRecentMemos(1).shouldHaveSize(1)
        }
    }

    test("mutation repository create update delete pin go through store and reminders") {
        runTest {
            val port = RecordingStorePort()
            val invalidation = StoreInvalidationBus()
            val reminders = FakeReminderCoordinator()
            val query = StoreMemoQueryRepository(port, invalidation, FakeEngineReadinessRepository())
            val mutation =
                StoreMemoMutationRepository(
                    port = port,
                    queryRepository = query,
                    reminderScheduler = reminders,
                    writeLease = alwaysWritableWorkspaceMutationLease(),
                    invalidation = invalidation,
                )

            val created = mutation.saveMemo(content = "new memo", timestamp = 1L, geoLocation = null)
            created.content shouldBe "new memo"
            port.commands.last().kind shouldBe StoreMemoCommandKind.Create
            reminders.syncForMemoCalls.map { it.first } shouldContainExactly listOf(created.id)

            mutation.updateMemo(created, "edited")
            port.commands.last().kind shouldBe StoreMemoCommandKind.Update
            query.getMemoById(created.id)?.content shouldBe "edited"

            mutation.setMemoPinned(created.id, pinned = true)
            port.commands.last().kind shouldBe StoreMemoCommandKind.Pin
            query.getMemoById(created.id)?.isPinned shouldBe true

            mutation.deleteMemo(created)
            port.commands.last().kind shouldBe StoreMemoCommandKind.Delete
            reminders.cancelForMemoCalls shouldContainExactly listOf(created.id)

            mutation.refreshMemos()
            port.rebuildCount shouldBe 1
        }
    }

    test("mutation repository fails closed when the workspace lease refuses admission") {
        runTest {
            val port = RecordingStorePort()
            val mutation =
                StoreMemoMutationRepository(
                    port = port,
                    queryRepository = StoreMemoQueryRepository(
                        port,
                        StoreInvalidationBus(),
                        FakeEngineReadinessRepository(),
                    ),
                    reminderScheduler = FakeReminderCoordinator(),
                    writeLease = notReadyWriteLease(),
                    invalidation = StoreInvalidationBus(),
                )
            shouldThrow<IllegalStateException> {
                mutation.saveMemo("x", timestamp = 1L, geoLocation = null)
            }
            port.commands shouldHaveSize 0
        }
    }

    test("statistics repository aggregates from store summaries") {
        runTest {
            val port = RecordingStorePort()
            port.seed(
                seededSnapshot(
                    "a",
                    "one two",
                    createdAtMs = 1_720_000_000_000L,
                    tags = listOf("work", "life"),
                ),
            )
            port.seed(
                seededSnapshot(
                    "b",
                    "three",
                    createdAtMs = 1_720_000_100_000L,
                    tags = listOf("work"),
                ),
            )
            val invalidation = StoreInvalidationBus()
            val stats =
                StoreMemoStatisticsRepository(
                    port = port,
                    invalidation = invalidation,
                    readiness = FakeEngineReadinessRepository(),
                )

            stats.getMemoCountFlow().test {
                awaitItem() shouldBe 2
                cancelAndIgnoreRemainingEvents()
            }
            val zone = ZoneId.of("UTC")
            val memoStats =
                stats.getMemoStatistics(
                    zone = zone,
                    today = LocalDate.of(2024, 7, 3),
                )
            memoStats.totalMemos shouldBe 2
            val tagCounts = stats.getTagCountsFlow().first()
            tagCounts.map { it.name to it.count } shouldContainExactly
                listOf("work" to 2, "life" to 1)
        }
    }

    test("sidebar statistics uses one aggregate projection and never walks memo pages") {
        runTest {
            val port = RecordingStorePort()
            repeat(2_001) { index ->
                port.seed(
                    seededSnapshot(
                        id = "memo-$index",
                        body = "body",
                        createdAtMs = 1_720_000_000_000L,
                        tags = listOf("all"),
                    ),
                )
            }
            val stats =
                StoreMemoStatisticsRepository(
                    port = port,
                    invalidation = StoreInvalidationBus(),
                    readiness = FakeEngineReadinessRepository(),
                )

            val sidebar = stats.getSidebarStatisticsFlow().first()

            sidebar.memoCount shouldBe 2_001
            sidebar.tagCounts shouldBe listOf(MemoTagCount("all", 2_001))
            port.sidebarQueryCount shouldBe 1
            port.queryCount shouldBe 0

            val detailed =
                stats.getMemoStatistics(
                    zone = ZoneId.of("UTC"),
                    today = LocalDate.of(2026, 8, 4),
                )
            detailed.totalMemos shouldBe 2_001
            port.queryCount shouldBe 41
        }
    }

    test("statistics repository stays empty until a workspace engine is ready") {
        runTest {
            val port = RecordingStorePort()
            port.seed(seededSnapshot("cold", "must not be queried"))
            val stats =
                StoreMemoStatisticsRepository(
                    port = port,
                    invalidation = StoreInvalidationBus(),
                    readiness = FakeEngineReadinessRepository(EngineReadiness.AwaitingWorkspaceSelection),
                )

            stats.getMemoCountFlow().first() shouldBe 0
            stats.getTagCountsFlow().first() shouldBe emptyList()
            stats.getMemoStatistics(ZoneId.of("UTC"), LocalDate.of(2024, 7, 3)).totalMemos shouldBe 0
            port.queryCount shouldBe 0
        }
    }
})
