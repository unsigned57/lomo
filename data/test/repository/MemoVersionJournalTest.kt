package com.lomo.data.repository

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */



import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.MemoRevisionCursor
import com.lomo.domain.model.MemoRevisionLifecycleState
import com.lomo.domain.model.MemoRevisionOrigin
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull

/*
 * Behavior Contract:
 * - Unit under test: MemoVersionJournal
 * - Behavior focus: append-only memo revision history, duplicate-state suppression across restore handoffs and edits,
 *   cursor pagination, settings-driven retention pruning and disable behavior, orphan-blob cleanup, imported
 *   refresh tracking, lightweight preview-only Room storage for revision listings, destructive snapshot clearing,
 *   and restore-command handoff metadata for the lifecycle outbox pipeline.
 * - Observable outcomes: page ordering and cursors, current-version markers, skipped duplicate revisions,
 *   imported deletion records, pruned history tails, cleaned blob files, preview-truncated history rows with full
 *   restore-command fidelity, asset bytes read from version blobs, and a single explicit restore-history handoff.
 * - TDD proof: Fails before the P1-4 lifecycle repair because revision restore is executed directly by
 *   MemoVersionJournal instead of being built as a VERSION_RESTORE lifecycle command and completed by outbox.
 * - Excludes: Room SQL mechanics, Compose/UI rendering, Git history integration, and workspace/DB restore execution.
 */
class MemoVersionJournalTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("appendLocalRevision deduplicates identical content and lists newest-first current-aware history") { `appendLocalRevision deduplicates identical content and lists newest-first current-aware history`() }

        test("appendLocalRevision stores long history rows as previews while restore command uses full markdown") { `appendLocalRevision stores long history rows as previews while restore command uses full markdown`() }

        test("buildRevisionRestoreCommand carries target memo and stored local attachment assets") { `buildRevisionRestoreCommand carries target memo and stored local attachment assets`() }

        test("recordRevisionRestoreHandoff reuses existing memo states instead of recording duplicate history entries") { `recordRevisionRestoreHandoff reuses existing memo states instead of recording duplicate history entries`() }

        test("buildRevisionRestoreCommand carries deleted target lifecycle state") { `buildRevisionRestoreCommand carries deleted target lifecycle state`() }

        test("buildRevisionRestoreCommand carries active target lifecycle state") { `buildRevisionRestoreCommand carries active target lifecycle state`() }

        test("buildRevisionRestoreCommand carries trashed target lifecycle state") { `buildRevisionRestoreCommand carries trashed target lifecycle state`() }

        test("restore handoff followed by imported refresh keeps edited history and marks only one current revision") { `restore handoff followed by imported refresh keeps edited history and marks only one current revision`() }

        test("appendImportedRefreshRevisions records changed and deleted memos but skips unchanged states") { `appendImportedRefreshRevisions records changed and deleted memos but skips unchanged states`() }

        test("appendLocalRevision stores blob metadata as managed relative paths") { `appendLocalRevision stores blob metadata as managed relative paths`() }

        test("listMemoRevisions pages history with stable cursor ordering") { `listMemoRevisions pages history with stable cursor ordering`() }

        test("appendLocalRevision prunes older revisions and garbage-collects orphan blobs") { `appendLocalRevision prunes older revisions and garbage-collects orphan blobs`() }

        test("appendLocalRevision reuses indexed asset fingerprint instead of reloading historical candidate assets") { `appendLocalRevision reuses indexed asset fingerprint instead of reloading historical candidate assets`() }

        test("appendLocalRevision skips recording when memo snapshots are disabled") { `appendLocalRevision skips recording when memo snapshots are disabled`() }

        test("appendLocalRevision prunes revisions older than configured retention age") { `appendLocalRevision prunes revisions older than configured retention age`() }

        test("appendLocalRevision prunes with targeted stale lookup instead of loading every revision") { `appendLocalRevision prunes with targeted stale lookup instead of loading every revision`() }

        test("clearAllMemoSnapshots removes revision history and blob files") { `clearAllMemoSnapshots removes revision history and blob files`() }

        test("appendLocalRevision rolls back partial version rows and blobs when revision asset write fails") { `appendLocalRevision rolls back partial version rows and blobs when revision asset write fails`() }

        test("readRevisionRestoreAssets reads stored revision blobs without workspace access") { `readRevisionRestoreAssets reads stored revision blobs without workspace access`() }

        test("recordRevisionRestoreHandoff preserves history when command target assets cannot be read from workspace") { `recordRevisionRestoreHandoff preserves history when command target assets cannot be read from workspace`() }

        test("recordRevisionRestoreHandoff records restored deletion through history without workspace mutation") { `recordRevisionRestoreHandoff records restored deletion through history without workspace mutation`() }
    }


    private lateinit var workspaceMediaAccess: JournalWorkspaceMediaAccess
    private lateinit var store: InMemoryMemoVersionStore
    private lateinit var blobRoot: File
    private lateinit var journal: MemoVersionJournal
    private lateinit var textProcessor: MemoTextProcessor

    private fun setUp() {
        workspaceMediaAccess = JournalWorkspaceMediaAccess()
        store = InMemoryMemoVersionStore()
        textProcessor = MemoTextProcessor()
        blobRoot = Files.createTempDirectory("memo-version-blobs").toFile()
        journal =
            MemoVersionJournal(
                store = store,
                blobRoot = blobRoot,
                workspaceMediaAccess = workspaceMediaAccess,
                memoTextProcessor = textProcessor,
                now = { Instant.parse("2026-03-27T09:00:00Z").toEpochMilli() },
                nextCommitId = store::nextCommitId,
                nextRevisionId = store::nextRevisionId,
                nextBatchId = store::nextBatchId,
            )
    }

    private fun `appendLocalRevision deduplicates identical content and lists newest-first current-aware history`() =
        runTest {
            val original =
                memo(
                    id = "memo-1",
                    content = "before",
                    rawContent = "- 09:00 before",
                )
            val updated =
                original.copy(
                    content = "after",
                    rawContent = "- 09:00 after",
                    updatedAt = original.updatedAt + 1,
                )

            journal.appendLocalRevision(
                memo = original,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_CREATE,
            )
            journal.appendLocalRevision(
                memo = original,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_EDIT,
            )
            journal.appendLocalRevision(
                memo = updated,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_EDIT,
            )

            val revisions = journal.listMemoRevisions(memo = updated, cursor = null, limit = 10).items

            revisions.size shouldBe 2
            revisions.map(MemoRevision::memoContent) shouldBe listOf("after", "before")
            revisions.map(MemoRevision::isCurrent) shouldBe listOf(true, false)
            revisions.map(MemoRevision::origin) shouldBe listOf(MemoRevisionOrigin.LOCAL_EDIT, MemoRevisionOrigin.LOCAL_CREATE)
        }

    private fun `appendLocalRevision stores long history rows as previews while restore command uses full markdown`() =
        runTest {
            val longLine = "before " + "x".repeat(HISTORY_PREVIEW_LIMIT + 64)
            val original =
                memo(
                    id = "memo-preview",
                    content = "$longLine\nsecond line kept for restore",
                    rawContent = "- 09:00 $longLine\nsecond line kept for restore",
                )
            val updated =
                original.copy(
                    content = "after",
                    rawContent = "- 09:00 after",
                    updatedAt = original.updatedAt + 1,
                    imageUrls = emptyList(),
                )

            journal.appendLocalRevision(
                memo = original,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_CREATE,
            )
            journal.appendLocalRevision(
                memo = updated,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_EDIT,
            )

            val history = journal.listMemoRevisions(memo = updated, cursor = null, limit = 10).items
            val originalRevision = history.last()
            val expectedPreview = expectedHistoryPreview(original.content)

            originalRevision.memoContent shouldBe expectedPreview
            requireNotNull(store.getRevision(originalRevision.revisionId)).memoContent shouldBe expectedPreview
            (expectedPreview.length < original.content.length).shouldBeTrue()

            val command = journal.buildRevisionRestoreCommand(
                currentMemo = updated,
                revisionId = originalRevision.revisionId,
            )
            val target = command.revisionRestoreTarget.shouldNotBeNull()

            command.operation shouldBe MemoLifecycleOperation.VERSION_RESTORE
            target.memo.content shouldBe original.content
            target.memo.rawContent shouldBe original.rawContent
            target.rawContent shouldBe original.rawContent
        }

    private fun `buildRevisionRestoreCommand carries target memo and stored local attachment assets`() =
        runTest {
            val targetBefore =
                memo(
                    id = "memo-target",
                    content = "before ![image](img_before.png)",
                    rawContent = "- 09:00 before ![image](img_before.png)",
                )
            val sibling =
                memo(
                    id = "memo-sibling",
                    content = "keep sibling",
                    rawContent = "- 10:00 keep sibling",
                    timestamp = targetBefore.timestamp + 60_000L,
                )
            workspaceMediaAccess.imageFiles["img_before.png"] = "before-image".toByteArray()

            journal.appendLocalRevision(
                memo = targetBefore,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_CREATE,
            )

            val targetAfter =
                targetBefore.copy(
                    content = "after",
                    rawContent = "- 09:00 after",
                    updatedAt = targetBefore.updatedAt + 1,
                    imageUrls = emptyList(),
                )
            workspaceMediaAccess.imageFiles.clear()
            journal.appendLocalRevision(
                memo = targetAfter,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_EDIT,
            )

            val originalRevisionId =
                journal
                    .listMemoRevisions(memo = targetAfter, cursor = null, limit = 10)
                    .items
                    .last()
                    .revisionId

            val command = journal.buildRevisionRestoreCommand(
                currentMemo = targetAfter,
                revisionId = originalRevisionId,
            )
            val target = command.revisionRestoreTarget.shouldNotBeNull()
            val rebuilt = command.toOutboxEntity().toLifecycleCommand()
            val assets = journal.readRevisionRestoreAssets(originalRevisionId)

            command.sourceMemo shouldBe targetAfter
            target.memo.id shouldBe targetBefore.id
            target.memo.content shouldBe targetBefore.content
            target.memo.rawContent shouldBe targetBefore.rawContent
            target.memo.dateKey shouldBe targetBefore.dateKey
            target.memo.updatedAt shouldBe targetBefore.updatedAt
            target.memo.imageUrls shouldBe targetBefore.imageUrls
            target.memo.isDeleted shouldBe false
            target.memo.timestamp shouldBe
                LocalDateTime
                    .of(2026, 3, 27, 9, 0)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            target.lifecycleState shouldBe MemoRevisionLifecycleState.ACTIVE
            rebuilt.metadata shouldBe command.metadata
            rebuilt.revisionRestoreTarget.shouldNotBeNull().rawContent shouldBe targetBefore.rawContent
            assets.size shouldBe 1
            assets.single().category shouldBe WorkspaceMediaCategory.IMAGE
            assets.single().filename shouldBe "img_before.png"
            readRestoreAssetBytes(assets.single()).decodeToString() shouldBe "before-image"
        }

    private fun `recordRevisionRestoreHandoff reuses existing memo states instead of recording duplicate history entries`() =
        runTest {
            val versionA =
                memo(
                    id = "memo-restore-dedupe",
                    content = "alpha",
                    rawContent = "- 09:00 alpha",
                )
            val versionB =
                versionA.copy(
                    content = "beta",
                    rawContent = "- 09:00 beta",
                    updatedAt = versionA.updatedAt + 1,
                )

            journal.appendLocalRevision(
                memo = versionA,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_CREATE,
            )
            journal.appendLocalRevision(
                memo = versionB,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_EDIT,
            )

            val initialHistory = journal.listMemoRevisions(memo = versionB, cursor = null, limit = 10).items
            val revisionA = initialHistory.last()
            val revisionB = initialHistory.first()

            journal.recordRevisionRestoreHandoff(
                journal.buildRevisionRestoreCommand(currentMemo = versionB, revisionId = revisionA.revisionId),
            )
            val currentAfterRestoreA = versionA.copy(updatedAt = versionB.updatedAt)
            journal.recordRevisionRestoreHandoff(
                journal.buildRevisionRestoreCommand(currentMemo = currentAfterRestoreA, revisionId = revisionB.revisionId),
            )

            val finalHistory = journal.listMemoRevisions(memo = versionB, cursor = null, limit = 10).items

            finalHistory.map(MemoRevision::memoContent) shouldBe listOf("beta", "alpha")
            finalHistory.size shouldBe 2
            store.revisionCountForMemo(versionA.id) shouldBe 2
        }

    private fun `buildRevisionRestoreCommand carries deleted target lifecycle state`() =
        runTest {
            val deleted =
                memo(
                    id = "memo-deleted-restore",
                    content = "deleted",
                    rawContent = "- 09:00 deleted",
                )

            journal.appendLocalRevision(
                memo = deleted,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_CREATE,
            )
            journal.appendImportedRefreshRevisions(
                changes =
                    listOf(
                        ImportedMemoRevisionChange.Delete(
                            memoId = deleted.id,
                            dateKey = deleted.dateKey,
                            rawContent = deleted.rawContent,
                            content = deleted.content,
                            timestamp = deleted.timestamp,
                            updatedAt = deleted.updatedAt,
                        ),
                    ),
                origin = MemoRevisionOrigin.IMPORT_REFRESH,
            )

            val deletedRevision =
                journal
                    .listMemoRevisions(memo = deleted, cursor = null, limit = 10)
                    .items
                    .first()

            val command =
                journal.buildRevisionRestoreCommand(
                    currentMemo = deleted,
                    revisionId = deletedRevision.revisionId,
                )
            val target = command.revisionRestoreTarget.shouldNotBeNull()

            target.lifecycleState shouldBe MemoRevisionLifecycleState.DELETED
            target.memo.isDeleted shouldBe true
            command.versionLifecycleState shouldBe MemoRevisionLifecycleState.DELETED
        }

    private fun `buildRevisionRestoreCommand carries active target lifecycle state`() =
        runTest {
            val original =
                memo(
                    id = "memo-active-restore-missing-stores",
                    content = "before",
                    rawContent = "- 09:00 before",
                )
            val updated =
                original.copy(
                    content = "after",
                    rawContent = "- 09:00 after",
                    updatedAt = original.updatedAt + 1,
                )

            journal.appendLocalRevision(
                memo = original,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_CREATE,
            )
            journal.appendLocalRevision(
                memo = updated,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_EDIT,
            )

            val originalRevisionId =
                journal
                    .listMemoRevisions(memo = updated, cursor = null, limit = 10)
                    .items
                    .last()
                    .revisionId

            val command = journal.buildRevisionRestoreCommand(currentMemo = updated, revisionId = originalRevisionId)
            val target = command.revisionRestoreTarget.shouldNotBeNull()

            target.lifecycleState shouldBe MemoRevisionLifecycleState.ACTIVE
            target.memo.isDeleted shouldBe false
            target.rawContent shouldBe original.rawContent
        }

    private fun `buildRevisionRestoreCommand carries trashed target lifecycle state`() =
        runTest {
            val active =
                memo(
                    id = "memo-trashed-restore-missing-stores",
                    content = "keep me",
                    rawContent = "- 09:00 keep me",
                )
            val trashed =
                active.copy(
                    isDeleted = true,
                    updatedAt = active.updatedAt + 1,
                )

            journal.appendLocalRevision(
                memo = active,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_CREATE,
            )
            journal.appendImportedRefreshRevisions(
                changes =
                    listOf(
                        ImportedMemoRevisionChange.Upsert(
                            memo = trashed,
                            lifecycleState = MemoRevisionLifecycleState.TRASHED,
                        ),
                    ),
                origin = MemoRevisionOrigin.IMPORT_REFRESH,
            )

            val trashedRevisionId =
                journal
                    .listMemoRevisions(memo = active, cursor = null, limit = 10)
                    .items
                    .first { revision -> revision.lifecycleState == MemoRevisionLifecycleState.TRASHED }
                    .revisionId

            val command = journal.buildRevisionRestoreCommand(currentMemo = active, revisionId = trashedRevisionId)
            val target = command.revisionRestoreTarget.shouldNotBeNull()

            target.lifecycleState shouldBe MemoRevisionLifecycleState.TRASHED
            target.memo.isDeleted shouldBe true
            target.rawContent shouldBe trashed.rawContent
        }

    private fun `restore handoff followed by imported refresh keeps edited history and marks only one current revision`() =
        runTest {
            val versionA =
                memo(
                    id = "memo-restore-refresh",
                    content = "alpha",
                    rawContent = "- 09:00 alpha",
                )
            val versionB =
                versionA.copy(
                    content = "beta",
                    rawContent = "- 09:00 beta",
                    updatedAt = versionA.updatedAt + 1,
                )

            journal.appendLocalRevision(
                memo = versionA,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_CREATE,
            )
            journal.appendLocalRevision(
                memo = versionB,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_EDIT,
            )

            val revisionA =
                journal
                    .listMemoRevisions(memo = versionB, cursor = null, limit = 10)
                    .items
                    .last()

            journal.recordRevisionRestoreHandoff(
                journal.buildRevisionRestoreCommand(currentMemo = versionB, revisionId = revisionA.revisionId),
            )
            journal.appendImportedRefreshRevisions(
                changes =
                    listOf(
                        ImportedMemoRevisionChange.Upsert(
                            memo = versionA,
                            lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                        ),
                    ),
                origin = MemoRevisionOrigin.IMPORT_REFRESH,
            )

            val finalHistory = journal.listMemoRevisions(memo = versionA, cursor = null, limit = 10).items

            finalHistory.map(MemoRevision::memoContent) shouldBe listOf("beta", "alpha")
            finalHistory.map(MemoRevision::isCurrent) shouldBe listOf(false, true)
            finalHistory.size shouldBe 2
            store.revisionCountForMemo(versionA.id) shouldBe 2
        }

    private fun `appendImportedRefreshRevisions records changed and deleted memos but skips unchanged states`() =
        runTest {
            val unchanged =
                memo(
                    id = "memo-stable",
                    content = "stable",
                    rawContent = "- 09:00 stable",
                )
            val deleted =
                memo(
                    id = "memo-deleted",
                    content = "deleted later",
                    rawContent = "- 10:00 deleted later",
                    timestamp = unchanged.timestamp + 60_000L,
                )
            journal.appendLocalRevision(
                memo = unchanged,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_CREATE,
            )
            journal.appendLocalRevision(
                memo = deleted,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_CREATE,
            )

            val changed =
                unchanged.copy(
                    content = "stable but imported",
                    rawContent = "- 09:00 stable but imported",
                    updatedAt = unchanged.updatedAt + 1,
                )

            journal.appendImportedRefreshRevisions(
                changes =
                    listOf(
                        ImportedMemoRevisionChange.Upsert(
                            memo = unchanged,
                            lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                        ),
                        ImportedMemoRevisionChange.Upsert(
                            memo = changed,
                            lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                        ),
                        ImportedMemoRevisionChange.Delete(
                            memoId = deleted.id,
                            dateKey = deleted.dateKey,
                            rawContent = deleted.rawContent,
                            content = deleted.content,
                            timestamp = deleted.timestamp,
                            updatedAt = deleted.updatedAt,
                        ),
                    ),
                origin = MemoRevisionOrigin.IMPORT_REFRESH,
            )

            val changedHistory = journal.listMemoRevisions(memo = changed, cursor = null, limit = 10).items
            changedHistory.map(MemoRevision::memoContent) shouldBe listOf("stable but imported", "stable")

            val deletedHistory = journal.listMemoRevisions(memo = deleted, cursor = null, limit = 10).items
            deletedHistory.size shouldBe 2
            deletedHistory.first().lifecycleState shouldBe MemoRevisionLifecycleState.DELETED
            deletedHistory.first().origin shouldBe MemoRevisionOrigin.IMPORT_REFRESH
        }

    private fun `appendLocalRevision stores blob metadata as managed relative paths`() =
        runTest {
            val memo =
                memo(
                    id = "memo-relative-blob",
                    content = "blob-path",
                    rawContent = "- 09:00 blob-path",
                )

            journal.appendLocalRevision(
                memo = memo,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_CREATE,
            )

            val latestRevision = requireNotNull(store.getLatestRevisionForMemo(memo.id))
            val rawContent =
                readMemoVersionBlobContent(
                    store = store,
                    blobRoot = blobRoot,
                    blobHash = latestRevision.rawMarkdownBlobHash,
                )

            rawContent shouldBe memo.rawContent
            (store.blobStoragePaths().all { path -> !File(path).isAbsolute }).shouldBeTrue()
        }

    private fun `listMemoRevisions pages history with stable cursor ordering`() =
        runTest {
            val original = memo(id = "memo-paged", content = "v1", rawContent = "- 09:00 v1")
            val second = original.copy(content = "v2", rawContent = "- 09:00 v2", updatedAt = original.updatedAt + 1)
            val third = second.copy(content = "v3", rawContent = "- 09:00 v3", updatedAt = second.updatedAt + 1)

            journal.appendLocalRevision(
                memo = original,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_CREATE,
            )
            journal.appendLocalRevision(
                memo = second,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_EDIT,
            )
            journal.appendLocalRevision(
                memo = third,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_EDIT,
            )

            val firstPage = journal.listMemoRevisions(memo = third, cursor = null, limit = 2)
            val secondPage =
                journal.listMemoRevisions(
                    memo = third,
                    cursor = firstPage.nextCursor,
                    limit = 2,
                )

            firstPage.items.map(MemoRevision::memoContent) shouldBe listOf("v3", "v2")
            firstPage.nextCursor.shouldNotBeNull()
            secondPage.items.map(MemoRevision::memoContent) shouldBe listOf("v1")
            secondPage.nextCursor shouldBe null
        }

    private fun `appendLocalRevision prunes older revisions and garbage-collects orphan blobs`() =
        runTest {
            val pruningJournal =
                MemoVersionJournal(
                    store = store,
                    blobRoot = blobRoot,
                    workspaceMediaAccess = workspaceMediaAccess,
                    memoTextProcessor = textProcessor,
                    now = { Instant.parse("2026-03-27T09:00:00Z").toEpochMilli() },
                    nextCommitId = store::nextCommitId,
                    nextRevisionId = store::nextRevisionId,
                    nextBatchId = store::nextBatchId,
                    maxRevisionsPerMemo = 2,
                )
            workspaceMediaAccess.imageFiles["img-old.png"] = "old-image".toByteArray()
            val original =
                memo(
                    id = "memo-pruned",
                    content = "v1 ![old](img-old.png)",
                    rawContent = "- 09:00 v1 ![old](img-old.png)",
                )
            val second = original.copy(content = "v2", rawContent = "- 09:00 v2", updatedAt = original.updatedAt + 1)
            val third = second.copy(content = "v3", rawContent = "- 09:00 v3", updatedAt = second.updatedAt + 1)

            pruningJournal.appendLocalRevision(
                memo = original,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_CREATE,
            )
            pruningJournal.appendLocalRevision(
                memo = second,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_EDIT,
            )
            pruningJournal.appendLocalRevision(
                memo = third,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_EDIT,
            )

            val revisions = pruningJournal.listMemoRevisions(memo = third, cursor = null, limit = 10).items

            revisions.map(MemoRevision::memoContent) shouldBe listOf("v3", "v2")
            store.revisionCountForMemo("memo-pruned") shouldBe 2
            store.blobCount() shouldBe 2
            blobFilesUnder(blobRoot).size shouldBe 2
        }

    private fun `appendLocalRevision reuses indexed asset fingerprint instead of reloading historical candidate assets`() =
        runTest {
            val fingerprintStore = HistoricalAssetLookupFailingMemoVersionStore()
            val fingerprintJournal =
                MemoVersionJournal(
                    store = fingerprintStore,
                    blobRoot = blobRoot,
                    workspaceMediaAccess = workspaceMediaAccess,
                    memoTextProcessor = textProcessor,
                    now = { Instant.parse("2026-03-27T09:00:00Z").toEpochMilli() },
                    nextCommitId = fingerprintStore::nextCommitId,
                    nextRevisionId = fingerprintStore::nextRevisionId,
                    nextBatchId = fingerprintStore::nextBatchId,
                )
            workspaceMediaAccess.imageFiles["img-alpha.png"] = "alpha-image".toByteArray()
            val versionA =
                memo(
                    id = "memo-fingerprint",
                    content = "alpha ![cover](img-alpha.png)",
                    rawContent = "- 09:00 alpha ![cover](img-alpha.png)",
                )
            val versionB =
                versionA.copy(
                    content = "beta",
                    rawContent = "- 09:00 beta",
                    updatedAt = versionA.updatedAt + 1,
                    imageUrls = emptyList(),
                )
            val restoredA =
                versionA.copy(
                    updatedAt = versionB.updatedAt + 1,
                )

            fingerprintJournal.appendLocalRevision(
                memo = versionA,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_CREATE,
            )
            fingerprintJournal.appendLocalRevision(
                memo = versionB,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_EDIT,
            )
            val historicalAlphaRevisionId =
                fingerprintJournal
                    .listMemoRevisions(memo = versionB, cursor = null, limit = 10)
                    .items
                    .last()
                    .revisionId
            fingerprintStore.failHistoricalAssetLookupFor(historicalAlphaRevisionId)

            fingerprintJournal.appendLocalRevision(
                memo = restoredA,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_RESTORE,
            )

            val revisions = fingerprintJournal.listMemoRevisions(memo = restoredA, cursor = null, limit = 10).items
            revisions.map(MemoRevision::memoContent) shouldBe listOf("beta", versionA.content)
            fingerprintStore.revisionCountForMemo(versionA.id) shouldBe 2
        }

    private fun `appendLocalRevision skips recording when memo snapshots are disabled`() =
        runTest {
            val disabledJournal =
                MemoVersionJournal(
                    store = store,
                    blobRoot = blobRoot,
                    workspaceMediaAccess = workspaceMediaAccess,
                    memoTextProcessor = textProcessor,
                    now = { Instant.parse("2026-03-27T09:00:00Z").toEpochMilli() },
                    nextCommitId = store::nextCommitId,
                    nextRevisionId = store::nextRevisionId,
                    nextBatchId = store::nextBatchId,
                    loadSnapshotSettings = {
                        MemoSnapshotRetentionSettings(
                            enabled = false,
                            maxCount = 20,
                            maxAgeDays = 30,
                        )
                    },
                )
            val memo =
                memo(
                    id = "memo-disabled",
                    content = "disabled",
                    rawContent = "- 09:00 disabled",
                )

            disabledJournal.appendLocalRevision(
                memo = memo,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_CREATE,
            )

            (disabledJournal.listMemoRevisions(memo = memo, cursor = null, limit = 10).items.isEmpty()).shouldBeTrue()
            store.revisionCountForMemo(memo.id) shouldBe 0
        }

    private fun `appendLocalRevision prunes revisions older than configured retention age`() =
        runTest {
            val createdAts =
                ArrayDeque(
                    listOf(
                        Instant.parse("2026-03-01T09:00:00Z").toEpochMilli(),
                        Instant.parse("2026-03-03T09:00:00Z").toEpochMilli(),
                        Instant.parse("2026-03-06T09:00:00Z").toEpochMilli(),
                    ),
                )
            val agePruningJournal =
                MemoVersionJournal(
                    store = store,
                    blobRoot = blobRoot,
                    workspaceMediaAccess = workspaceMediaAccess,
                    memoTextProcessor = textProcessor,
                    now = { createdAts.removeFirst() },
                    nextCommitId = store::nextCommitId,
                    nextRevisionId = store::nextRevisionId,
                    nextBatchId = store::nextBatchId,
                    loadSnapshotSettings = {
                        MemoSnapshotRetentionSettings(
                            enabled = true,
                            maxCount = 10,
                            maxAgeDays = 3,
                        )
                    },
                )
            val original = memo(id = "memo-aged", content = "v1", rawContent = "- 09:00 v1")
            val second = original.copy(content = "v2", rawContent = "- 09:00 v2", updatedAt = original.updatedAt + 1)
            val third = second.copy(content = "v3", rawContent = "- 09:00 v3", updatedAt = second.updatedAt + 1)

            agePruningJournal.appendLocalRevision(
                memo = original,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_CREATE,
            )
            agePruningJournal.appendLocalRevision(
                memo = second,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_EDIT,
            )
            agePruningJournal.appendLocalRevision(
                memo = third,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_EDIT,
            )

            val revisions = agePruningJournal.listMemoRevisions(memo = third, cursor = null, limit = 10).items

            revisions.map(MemoRevision::memoContent) shouldBe listOf("v3", "v2")
            store.revisionCountForMemo("memo-aged") shouldBe 2
        }

    private fun `appendLocalRevision prunes with targeted stale lookup instead of loading every revision`() =
        runTest {
            val pruningStore = FullHistoryLookupFailingMemoVersionStore()
            val pruningJournal =
                MemoVersionJournal(
                    store = pruningStore,
                    blobRoot = blobRoot,
                    workspaceMediaAccess = workspaceMediaAccess,
                    memoTextProcessor = textProcessor,
                    now = { Instant.parse("2026-03-27T09:00:00Z").toEpochMilli() },
                    nextCommitId = pruningStore::nextCommitId,
                    nextRevisionId = pruningStore::nextRevisionId,
                    nextBatchId = pruningStore::nextBatchId,
                    maxRevisionsPerMemo = 2,
                )
            val original = memo(id = "memo-targeted-prune", content = "v1", rawContent = "- 09:00 v1")
            val second = original.copy(content = "v2", rawContent = "- 09:00 v2", updatedAt = original.updatedAt + 1)
            val third = second.copy(content = "v3", rawContent = "- 09:00 v3", updatedAt = second.updatedAt + 1)

            pruningJournal.appendLocalRevision(
                memo = original,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_CREATE,
            )
            pruningJournal.appendLocalRevision(
                memo = second,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_EDIT,
            )
            pruningStore.failOnFullHistoryLookup = true

            pruningJournal.appendLocalRevision(
                memo = third,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_EDIT,
            )

            val revisions = pruningJournal.listMemoRevisions(memo = third, cursor = null, limit = 10).items
            revisions.map(MemoRevision::memoContent) shouldBe listOf("v3", "v2")
            pruningStore.revisionCountForMemo(original.id) shouldBe 2
        }

    private fun `clearAllMemoSnapshots removes revision history and blob files`() =
        runTest {
            val memo = memo(id = "memo-clear", content = "clear me", rawContent = "- 09:00 clear me")

            journal.appendLocalRevision(
                memo = memo,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_CREATE,
            )

            journal.clearAllMemoSnapshots()

            (journal.listMemoRevisions(memo = memo, cursor = null, limit = 10).items.isEmpty()).shouldBeTrue()
            store.blobCount() shouldBe 0
            (blobFilesUnder(blobRoot).isEmpty()).shouldBeTrue()
        }

    private fun `appendLocalRevision rolls back partial version rows and blobs when revision asset write fails`() =
        runTest {
            val failingStore = ReplaceAssetsFailingMemoVersionStore()
            val transactionalJournal =
                MemoVersionJournal(
                    store = failingStore,
                    blobRoot = blobRoot,
                    workspaceMediaAccess = workspaceMediaAccess,
                    memoTextProcessor = textProcessor,
                    runInTransaction = failingStore::runRollbackableTransaction,
                    now = { Instant.parse("2026-03-27T09:00:00Z").toEpochMilli() },
                    nextCommitId = failingStore::nextCommitId,
                    nextRevisionId = failingStore::nextRevisionId,
                    nextBatchId = failingStore::nextBatchId,
                )
            val memo =
                memo(
                    id = "memo-append-atomicity",
                    content = "before ![image](img_before.png)",
                    rawContent = "- 09:00 before ![image](img_before.png)",
                )
            workspaceMediaAccess.imageFiles["img_before.png"] = "before-image".toByteArray()
            failingStore.failOnReplaceAssets = true

            val failure =
                runCatching {
                    transactionalJournal.appendLocalRevision(
                        memo = memo,
                        lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                        origin = MemoRevisionOrigin.LOCAL_CREATE,
                    )
                }.exceptionOrNull()

            (failure is IOException).shouldBeTrue()
            failingStore.revisionCountForMemo(memo.id) shouldBe 0
            failingStore.blobCount() shouldBe 0
            (blobFilesUnder(blobRoot).isEmpty()).shouldBeTrue()
        }

    private fun `readRevisionRestoreAssets reads stored revision blobs without workspace access`() =
        runTest {
            val original =
                memo(
                    id = "memo-restore-assets",
                    content = "before ![image](img_before.png)",
                    rawContent = "- 09:00 before ![image](img_before.png)",
                )
            workspaceMediaAccess.imageFiles["img_before.png"] = "before-image".toByteArray()

            journal.appendLocalRevision(
                memo = original,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_CREATE,
            )
            val originalRevisionId =
                journal
                    .listMemoRevisions(memo = original, cursor = null, limit = 10)
                    .items
                    .single()
                    .revisionId
            workspaceMediaAccess.imageFiles.clear()

            val assets = journal.readRevisionRestoreAssets(originalRevisionId)

            assets.size shouldBe 1
            assets.single().category shouldBe WorkspaceMediaCategory.IMAGE
            assets.single().filename shouldBe "img_before.png"
            readRestoreAssetBytes(assets.single()).decodeToString() shouldBe "before-image"
            workspaceMediaAccess.imageFiles shouldBe emptyMap()
        }

    private fun `recordRevisionRestoreHandoff preserves history when command target assets cannot be read from workspace`() =
        runTest {
            val original =
                memo(
                    id = "memo-restore-reuse",
                    content = "before ![image](img_before.png)",
                    rawContent = "- 09:00 before ![image](img_before.png)",
                )
            val updated =
                original.copy(
                    content = "after",
                    rawContent = "- 09:00 after",
                    updatedAt = original.updatedAt + 1,
                    imageUrls = emptyList(),
                )
            workspaceMediaAccess.imageFiles["img_before.png"] = "before-image".toByteArray()

            journal.appendLocalRevision(
                memo = original,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_CREATE,
            )
            workspaceMediaAccess.imageFiles.clear()
            journal.appendLocalRevision(
                memo = updated,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_EDIT,
            )
            val originalRevisionId =
                journal
                    .listMemoRevisions(memo = updated, cursor = null, limit = 10)
                    .items
                    .last()
                    .revisionId
            val handoffOnlyJournal =
                MemoVersionJournal(
                    store = store,
                    blobRoot = blobRoot,
                    workspaceMediaAccess = ThrowingWorkspaceMediaAccess,
                    memoTextProcessor = textProcessor,
                    now = { Instant.parse("2026-03-27T09:00:00Z").toEpochMilli() },
                    nextCommitId = store::nextCommitId,
                    nextRevisionId = store::nextRevisionId,
                    nextBatchId = store::nextBatchId,
                )

            handoffOnlyJournal.recordRevisionRestoreHandoff(
                handoffOnlyJournal.buildRevisionRestoreCommand(
                    currentMemo = updated,
                    revisionId = originalRevisionId,
                ),
            )

            val revisions = handoffOnlyJournal.listMemoRevisions(memo = original, cursor = null, limit = 10).items
            revisions.map(MemoRevision::memoContent) shouldBe listOf("after", original.content)
            store.revisionCountForMemo(original.id) shouldBe 2
        }

    private fun `recordRevisionRestoreHandoff records restored deletion through history without workspace mutation`() =
        runTest {
            val active =
                memo(
                    id = "memo-restore-delete",
                    content = "remove me",
                    rawContent = "- 09:00 remove me",
                )
            journal.appendLocalRevision(
                memo = active,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_CREATE,
            )
            journal.appendImportedRefreshRevisions(
                changes =
                    listOf(
                        ImportedMemoRevisionChange.Delete(
                            memoId = active.id,
                            dateKey = active.dateKey,
                            rawContent = active.rawContent,
                            content = active.content,
                            timestamp = active.timestamp,
                            updatedAt = active.updatedAt + 1,
                        ),
                    ),
                origin = MemoRevisionOrigin.IMPORT_REFRESH,
            )
            val deletedRevisionId =
                journal
                    .listMemoRevisions(memo = active, cursor = null, limit = 10)
                    .items
                    .first { revision -> revision.lifecycleState == MemoRevisionLifecycleState.DELETED }
                    .revisionId

            journal.recordRevisionRestoreHandoff(
                journal.buildRevisionRestoreCommand(
                    currentMemo = active,
                    revisionId = deletedRevisionId,
                ),
            )

            val revisions = journal.listMemoRevisions(memo = active.copy(isDeleted = true), cursor = null, limit = 10).items
            revisions.map(MemoRevision::lifecycleState) shouldBe
                listOf(MemoRevisionLifecycleState.DELETED, MemoRevisionLifecycleState.ACTIVE)
            workspaceMediaAccess.imageFiles shouldBe emptyMap()
            workspaceMediaAccess.voiceFiles shouldBe emptyMap()
        }
}

private suspend fun readRestoreAssetBytes(
    asset: MemoRevisionRestoreAsset,
): ByteArray {
    val output = ByteArrayOutputStream()
    asset.writeTo(output)
    return output.toByteArray()
}

private fun memo(
    id: String,
    content: String,
    rawContent: String,
    timestamp: Long = Instant.parse("2026-03-27T09:00:00Z").toEpochMilli(),
): Memo =
    Memo(
        id = id,
        timestamp = timestamp,
        updatedAt = timestamp,
        content = content,
        rawContent = rawContent,
        dateKey = "2026_03_27",
        imageUrls = MemoTextProcessor().extractImages(content),
    )

private open class JournalWorkspaceMediaAccess : WorkspaceMediaAccess {
    val imageFiles = linkedMapOf<String, ByteArray>()
    val voiceFiles = linkedMapOf<String, ByteArray>()

    override suspend fun listFiles(category: WorkspaceMediaCategory): List<WorkspaceMediaDescriptor> =
        currentMap(category).map { (filename, bytes) ->
            WorkspaceMediaDescriptor(filename = filename, sizeBytes = bytes.size.toLong())
        }

    override suspend fun listFilenames(category: WorkspaceMediaCategory): List<String> =
        currentMap(category).keys.sorted()

    override suspend fun deleteFile(
        category: WorkspaceMediaCategory,
        filename: String,
    ) {
        currentMap(category).remove(filename)
    }

    override suspend fun readFileToStream(
        category: WorkspaceMediaCategory,
        filename: String,
        destination: OutputStream,
    ): Boolean {
        val bytes = currentMap(category)[filename] ?: return false
        destination.write(bytes)
        return true
    }

    override suspend fun writeFileFromStream(
        category: WorkspaceMediaCategory,
        filename: String,
        source: suspend (OutputStream) -> Unit,
    ) {
        val output = java.io.ByteArrayOutputStream()
        source(output)
        currentMap(category)[filename] = output.toByteArray()
    }

    private fun currentMap(category: WorkspaceMediaCategory): LinkedHashMap<String, ByteArray> =
        when (category) {
            WorkspaceMediaCategory.IMAGE -> imageFiles
            WorkspaceMediaCategory.VOICE -> voiceFiles
        }
}

private open class InMemoryMemoVersionStore : MemoVersionStore {
    private val commits = linkedMapOf<String, MemoVersionCommitRecord>()
    private val revisions = linkedMapOf<String, MemoVersionRevisionRecord>()
    private val assets = linkedMapOf<String, MutableList<MemoVersionAssetRecord>>()
    private val blobs = linkedMapOf<String, MemoVersionBlobRecord>()
    private var commitCounter = 0
    private var revisionCounter = 0
    private var batchCounter = 0

    fun nextCommitId(): String = "commit-${++commitCounter}"

    fun nextRevisionId(): String = "revision-${++revisionCounter}"

    fun nextBatchId(): String = "batch-${++batchCounter}"

    open override suspend fun insertCommit(record: MemoVersionCommitRecord) {
        commits[record.commitId] = record
    }

    open override suspend fun insertRevision(record: MemoVersionRevisionRecord) {
        revisions[record.revisionId] = record
    }

    open override suspend fun replaceAssets(
        revisionId: String,
        records: List<MemoVersionAssetRecord>,
    ) {
        assets[revisionId] = records.toMutableList()
    }

    override suspend fun getBlob(blobHash: String): MemoVersionBlobRecord? = blobs[blobHash]

    open override suspend fun insertBlob(record: MemoVersionBlobRecord) {
        blobs[record.blobHash] = record
    }

    override suspend fun getRevision(revisionId: String): MemoVersionRevisionRecord? = revisions[revisionId]

    override suspend fun getCommit(commitId: String): MemoVersionCommitRecord? = commits[commitId]

    override suspend fun listRevisionHistoryForMemo(
        memoId: String,
        cursor: MemoRevisionCursor?,
        limit: Int,
    ): List<MemoVersionRevisionHistoryRecord> =
        revisions.values
            .filter { it.memoId == memoId }
            .sortedWith(
                compareByDescending<MemoVersionRevisionRecord> { it.createdAt }
                    .thenByDescending { it.revisionId },
            ).let { sorted ->
                val startIndex =
                    cursor?.let { nonNullCursor ->
                        val cursorIndex =
                            sorted.indexOfFirst { revision ->
                                revision.createdAt == nonNullCursor.createdAt &&
                                    revision.revisionId == nonNullCursor.revisionId
                            }
                        if (cursorIndex >= 0) {
                            cursorIndex + 1
                        } else {
                            sorted.size
                        }
                    } ?: 0
                sorted
                    .drop(startIndex)
                    .take(limit)
                    .map { revision ->
                        val commit = commits.getValue(revision.commitId)
                        MemoVersionRevisionHistoryRecord(
                            revisionId = revision.revisionId,
                            parentRevisionId = revision.parentRevisionId,
                            memoId = revision.memoId,
                            commitId = revision.commitId,
                            batchId = commit.batchId,
                            origin = commit.origin,
                            summary = commit.summary,
                            lifecycleState = revision.lifecycleState,
                            memoContent = revision.memoContent,
                            contentHash = revision.contentHash,
                            createdAt = revision.createdAt,
                        )
                    }
            }

    override suspend fun getLatestRevisionForMemo(memoId: String): MemoVersionRevisionRecord? =
        revisions.values
            .filter { it.memoId == memoId }
            .maxWithOrNull(compareBy<MemoVersionRevisionRecord> { it.createdAt }.thenBy { it.revisionId })

    override suspend fun findEquivalentRevisionsForMemo(
        memoId: String,
        lifecycleState: MemoRevisionLifecycleState,
        rawMarkdownBlobHash: String,
        contentHash: String,
        assetFingerprint: String,
    ): List<MemoVersionRevisionRecord> =
        revisions.values
            .filter { revision ->
                revision.memoId == memoId &&
                    revision.lifecycleState == lifecycleState &&
                    revision.rawMarkdownBlobHash == rawMarkdownBlobHash &&
                    revision.contentHash == contentHash &&
                    (revision.assetFingerprint == assetFingerprint || revision.assetFingerprint == null)
            }.sortedWith(
                compareByDescending<MemoVersionRevisionRecord> { it.createdAt }
                    .thenByDescending { it.revisionId },
            )

    override suspend fun listAssetsForRevision(revisionId: String): List<MemoVersionAssetRecord> =
        assets[revisionId].orEmpty().toList()

    override suspend fun listStaleRevisionsForMemo(
        memoId: String,
        retainCount: Int,
        olderThanCreatedAt: Long?,
    ): List<MemoVersionRevisionRecord> =
        revisions.values
            .filter { it.memoId == memoId }
            .sortedWith(
                compareByDescending<MemoVersionRevisionRecord> { it.createdAt }
                    .thenByDescending { it.revisionId },
            ).filterIndexed { index, revision ->
                val exceedsCount = retainCount > 0 && index >= retainCount
                val exceedsAge = olderThanCreatedAt != null && revision.createdAt < olderThanCreatedAt
                exceedsCount || exceedsAge
            }

    override suspend fun listAllRevisionsForMemo(memoId: String): List<MemoVersionRevisionRecord> =
        revisions.values
            .filter { it.memoId == memoId }
            .sortedWith(
                compareByDescending<MemoVersionRevisionRecord> { it.createdAt }
                    .thenByDescending { it.revisionId },
            )

    override suspend fun listAssetsForRevisionIds(revisionIds: List<String>): List<MemoVersionAssetRecord> =
        revisionIds.flatMap { revisionId -> assets[revisionId].orEmpty() }

    override suspend fun deleteAssetsByRevisionIds(revisionIds: List<String>) {
        revisionIds.forEach(assets::remove)
    }

    override suspend fun deleteRevisionsByIds(revisionIds: List<String>) {
        revisionIds.forEach(revisions::remove)
    }

    override suspend fun isBlobReferenced(blobHash: String): Boolean =
        revisions.values.any { it.rawMarkdownBlobHash == blobHash } ||
            assets.values.flatten().any { it.blobHash == blobHash }

    override suspend fun deleteBlob(blobHash: String) {
        blobs.remove(blobHash)
    }

    override suspend fun clearAll() {
        commits.clear()
        revisions.clear()
        assets.clear()
        blobs.clear()
    }

    fun revisionCountForMemo(memoId: String): Int = revisions.values.count { it.memoId == memoId }

    fun blobCount(): Int = blobs.size

    fun blobStoragePaths(): List<String> = blobs.values.map(MemoVersionBlobRecord::storagePath)

    suspend fun runRollbackableTransaction(block: suspend () -> Unit) {
        val snapshot =
            InMemoryMemoVersionStoreSnapshot(
                commits = LinkedHashMap(commits),
                revisions = LinkedHashMap(revisions),
                assets = assets.mapValuesTo(linkedMapOf()) { (_, records) -> records.toMutableList() },
                blobs = LinkedHashMap(blobs),
                commitCounter = commitCounter,
                revisionCounter = revisionCounter,
                batchCounter = batchCounter,
            )
        try {
            block()
        } catch (throwable: Throwable) {
            commits.clear()
            commits.putAll(snapshot.commits)
            revisions.clear()
            revisions.putAll(snapshot.revisions)
            assets.clear()
            assets.putAll(snapshot.assets.mapValuesTo(linkedMapOf()) { (_, records) -> records.toMutableList() })
            blobs.clear()
            blobs.putAll(snapshot.blobs)
            commitCounter = snapshot.commitCounter
            revisionCounter = snapshot.revisionCounter
            batchCounter = snapshot.batchCounter
            throw throwable
        }
    }
}

private class ReplaceAssetsFailingMemoVersionStore : InMemoryMemoVersionStore() {
    var failOnReplaceAssets: Boolean = false

    override suspend fun replaceAssets(
        revisionId: String,
        records: List<MemoVersionAssetRecord>,
    ) {
        if (failOnReplaceAssets) {
            throw IOException("boom-assets: $revisionId")
        }
        super.replaceAssets(revisionId, records)
    }
}

private class HistoricalAssetLookupFailingMemoVersionStore : InMemoryMemoVersionStore() {
    private val forbiddenRevisionIds = linkedSetOf<String>()

    fun failHistoricalAssetLookupFor(revisionId: String) {
        forbiddenRevisionIds += revisionId
    }

    override suspend fun listAssetsForRevision(revisionId: String): List<MemoVersionAssetRecord> {
        check(revisionId !in forbiddenRevisionIds) {
            "Historical asset lookup should not be needed for revision=$revisionId"
        }
        return super.listAssetsForRevision(revisionId)
    }
}

private class FullHistoryLookupFailingMemoVersionStore : InMemoryMemoVersionStore() {
    var failOnFullHistoryLookup: Boolean = false

    override suspend fun listAllRevisionsForMemo(memoId: String): List<MemoVersionRevisionRecord> {
        check(!failOnFullHistoryLookup) { "Full history lookup should not be needed for memo=$memoId" }
        return super.listAllRevisionsForMemo(memoId)
    }
}

private data class InMemoryMemoVersionStoreSnapshot(
    val commits: LinkedHashMap<String, MemoVersionCommitRecord>,
    val revisions: LinkedHashMap<String, MemoVersionRevisionRecord>,
    val assets: LinkedHashMap<String, MutableList<MemoVersionAssetRecord>>,
    val blobs: LinkedHashMap<String, MemoVersionBlobRecord>,
    val commitCounter: Int,
    val revisionCounter: Int,
    val batchCounter: Int,
)

private fun blobFilesUnder(root: File): List<File> =
    root
        .walkTopDown()
        .filter(File::isFile)
        .toList()

private const val HISTORY_PREVIEW_LIMIT = 280
private const val HISTORY_PREVIEW_SUFFIX = "..."

private fun expectedHistoryPreview(content: String): String =
    if (content.length <= HISTORY_PREVIEW_LIMIT) {
        content
    } else {
        content.take(HISTORY_PREVIEW_LIMIT - HISTORY_PREVIEW_SUFFIX.length) + HISTORY_PREVIEW_SUFFIX
    }
