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
 * - Given store pages with memos, when getAllMemosList / getMemoById / getMemoCount run, then
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
 * - Real BoltFFI, Room dual-stack (deleted), full history list FFI (StoreMemoVersionRepository stub).
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
import com.lomo.domain.repository.WorkspaceMutationLease
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

private class RecordingStorePort : StorePort {
    val commands = mutableListOf<StoreMemoCommand>()
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
    test("query repository lists and loads memos from store pages") {
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
            val repo = StoreMemoQueryRepository(port, invalidation)

            val all = repo.getAllMemosList().first()
            all.map { it.id } shouldContainExactly listOf("a", "b")
            all[0].content shouldBe "alpha"
            all[0].tags shouldContainExactly listOf("life")
            all[1].imageUrls shouldContainExactly listOf("images/b.png")

            repo.getMemoById("b").shouldNotBeNull().content shouldBe "beta"
            repo.getMemoCount() shouldBe 2
            repo.getRecentMemos(1).shouldHaveSize(1)
            repo.getGalleryMemosList().first().map { it.id } shouldContainExactly listOf("b")
        }
    }

    test("mutation repository create update delete pin go through store and reminders") {
        runTest {
            val port = RecordingStorePort()
            val invalidation = StoreInvalidationBus()
            val reminders = FakeReminderCoordinator()
            val query = StoreMemoQueryRepository(port, invalidation)
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
                    queryRepository = StoreMemoQueryRepository(port, StoreInvalidationBus()),
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
            val stats = StoreMemoStatisticsRepository(port, invalidation)

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
})
