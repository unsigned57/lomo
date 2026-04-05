package com.lomo.data.repository

import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.MemoRevisionCursor
import com.lomo.domain.model.MemoRevisionLifecycleState
import com.lomo.domain.model.MemoRevisionOrigin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant

/*
 * Test Contract:
 * - Unit under test: MemoVersionJournal
 * - Behavior focus: append-only memo revision history, duplicate-state suppression across restores and edits,
 *   cursor pagination, settings-driven retention pruning and disable behavior, orphan-blob cleanup, imported
 *   refresh tracking, lightweight preview-only Room storage for revision listings, destructive snapshot clearing,
 *   and scoped memo restore with referenced local attachments.
 * - Observable outcomes: page ordering and cursors, current-version markers, skipped duplicate revisions,
 *   imported deletion records, pruned history tails, cleaned blob files, preview-truncated history rows with full
 *   restore fidelity, restored markdown content, rollback of partially applied restore side effects, and restore
 *   failure preventing a synthetic restore revision.
 * - Red phase: Fails before the fix because restoring or editing back to an older memo state records duplicate
 *   revisions for content and attachments that already exist in history, and revision rows still persist full memo
 *   bodies instead of compact previews.
 * - Excludes: Room SQL mechanics, Compose/UI rendering, and Git history integration.
 */
class MemoVersionJournalTest {
    private lateinit var markdownStorageDataSource: JournalMarkdownStorageDataSource
    private lateinit var workspaceMediaAccess: JournalWorkspaceMediaAccess
    private lateinit var store: InMemoryMemoVersionStore
    private lateinit var blobRoot: File
    private lateinit var journal: MemoVersionJournal
    private lateinit var textProcessor: MemoTextProcessor

    @Before
    fun setUp() {
        markdownStorageDataSource = JournalMarkdownStorageDataSource()
        workspaceMediaAccess = JournalWorkspaceMediaAccess()
        store = InMemoryMemoVersionStore()
        textProcessor = MemoTextProcessor()
        blobRoot = Files.createTempDirectory("memo-version-blobs").toFile()
        journal =
            MemoVersionJournal(
                store = store,
                blobRoot = blobRoot,
                markdownStorageDataSource = markdownStorageDataSource,
                workspaceMediaAccess = workspaceMediaAccess,
                memoTextProcessor = textProcessor,
                now = { Instant.parse("2026-03-27T09:00:00Z").toEpochMilli() },
                nextCommitId = store::nextCommitId,
                nextRevisionId = store::nextRevisionId,
                nextBatchId = store::nextBatchId,
            )
    }

    @Test
    fun `appendLocalRevision deduplicates identical content and lists newest-first current-aware history`() =
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

            assertEquals(2, revisions.size)
            assertEquals(listOf("after", "before"), revisions.map(MemoRevision::memoContent))
            assertEquals(listOf(true, false), revisions.map(MemoRevision::isCurrent))
            assertEquals(
                listOf(MemoRevisionOrigin.LOCAL_EDIT, MemoRevisionOrigin.LOCAL_CREATE),
                revisions.map(MemoRevision::origin),
            )
        }

    @Test
    fun `appendLocalRevision stores long history rows as previews while restore still uses full markdown`() =
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
            markdownStorageDataSource.mainFiles["2026_03_27.md"] = original.rawContent

            journal.appendLocalRevision(
                memo = original,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_CREATE,
            )
            markdownStorageDataSource.mainFiles["2026_03_27.md"] = updated.rawContent
            journal.appendLocalRevision(
                memo = updated,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_EDIT,
            )

            val history = journal.listMemoRevisions(memo = updated, cursor = null, limit = 10).items
            val originalRevision = history.last()
            val expectedPreview = expectedHistoryPreview(original.content)

            assertEquals(expectedPreview, originalRevision.memoContent)
            assertEquals(
                expectedPreview,
                requireNotNull(store.getRevision(originalRevision.revisionId)).memoContent,
            )
            assertTrue(expectedPreview.length < original.content.length)

            journal.restoreMemoRevision(
                currentMemo = updated,
                revisionId = originalRevision.revisionId,
            )

            assertEquals(original.rawContent, markdownStorageDataSource.mainFiles.getValue("2026_03_27.md"))
        }

    @Test
    fun `restoreRevision rewrites only target memo and restores missing local attachments`() =
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
            markdownStorageDataSource.mainFiles["2026_03_27.md"] =
                listOf(targetBefore.rawContent, sibling.rawContent).joinToString("\n")
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
            markdownStorageDataSource.mainFiles["2026_03_27.md"] =
                listOf(targetAfter.rawContent, sibling.rawContent).joinToString("\n")
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

            journal.restoreMemoRevision(
                currentMemo = targetAfter,
                revisionId = originalRevisionId,
            )

            assertEquals(
                listOf(targetBefore.rawContent, sibling.rawContent).joinToString("\n"),
                markdownStorageDataSource.mainFiles["2026_03_27.md"],
            )
            assertTrue(workspaceMediaAccess.imageFiles.containsKey("img_before.png"))
            assertEquals("before-image", workspaceMediaAccess.imageFiles.getValue("img_before.png").decodeToString())
        }

    @Test
    fun `restoreRevision reuses existing memo states instead of recording duplicate history entries`() =
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
            markdownStorageDataSource.mainFiles["2026_03_27.md"] = versionA.rawContent

            journal.appendLocalRevision(
                memo = versionA,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_CREATE,
            )
            markdownStorageDataSource.mainFiles["2026_03_27.md"] = versionB.rawContent
            journal.appendLocalRevision(
                memo = versionB,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_EDIT,
            )

            val initialHistory = journal.listMemoRevisions(memo = versionB, cursor = null, limit = 10).items
            val revisionA = initialHistory.last()
            val revisionB = initialHistory.first()

            journal.restoreMemoRevision(currentMemo = versionB, revisionId = revisionA.revisionId)
            val currentAfterRestoreA = versionA.copy(updatedAt = versionB.updatedAt)
            journal.restoreMemoRevision(currentMemo = currentAfterRestoreA, revisionId = revisionB.revisionId)

            val finalHistory = journal.listMemoRevisions(memo = versionB, cursor = null, limit = 10).items

            assertEquals(listOf("beta", "alpha"), finalHistory.map(MemoRevision::memoContent))
            assertEquals(2, finalHistory.size)
            assertEquals(2, store.revisionCountForMemo(versionA.id))
            assertEquals(versionB.rawContent, markdownStorageDataSource.mainFiles.getValue("2026_03_27.md"))
        }

    @Test
    fun `restoreRevision followed by imported refresh keeps edited history and marks only one current revision`() =
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
            markdownStorageDataSource.mainFiles["2026_03_27.md"] = versionA.rawContent

            journal.appendLocalRevision(
                memo = versionA,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_CREATE,
            )
            markdownStorageDataSource.mainFiles["2026_03_27.md"] = versionB.rawContent
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

            journal.restoreMemoRevision(currentMemo = versionB, revisionId = revisionA.revisionId)
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

            assertEquals(listOf("beta", "alpha"), finalHistory.map(MemoRevision::memoContent))
            assertEquals(listOf(false, true), finalHistory.map(MemoRevision::isCurrent))
            assertEquals(2, finalHistory.size)
            assertEquals(2, store.revisionCountForMemo(versionA.id))
        }

    @Test
    fun `appendImportedRefreshRevisions records changed and deleted memos but skips unchanged states`() =
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
            assertEquals(listOf("stable but imported", "stable"), changedHistory.map(MemoRevision::memoContent))

            val deletedHistory = journal.listMemoRevisions(memo = deleted, cursor = null, limit = 10).items
            assertEquals(2, deletedHistory.size)
            assertEquals(MemoRevisionLifecycleState.DELETED, deletedHistory.first().lifecycleState)
            assertEquals(MemoRevisionOrigin.IMPORT_REFRESH, deletedHistory.first().origin)
        }

    @Test
    fun `appendLocalRevision stores blob metadata as managed relative paths`() =
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

            assertEquals(memo.rawContent, rawContent)
            assertTrue(store.blobStoragePaths().all { path -> !File(path).isAbsolute })
        }

    @Test
    fun `listMemoRevisions pages history with stable cursor ordering`() =
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

            assertEquals(listOf("v3", "v2"), firstPage.items.map(MemoRevision::memoContent))
            assertNotNull(firstPage.nextCursor)
            assertEquals(listOf("v1"), secondPage.items.map(MemoRevision::memoContent))
            assertEquals(null, secondPage.nextCursor)
        }

    @Test
    fun `appendLocalRevision prunes older revisions and garbage-collects orphan blobs`() =
        runTest {
            val pruningJournal =
                MemoVersionJournal(
                    store = store,
                    blobRoot = blobRoot,
                    markdownStorageDataSource = markdownStorageDataSource,
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

            assertEquals(listOf("v3", "v2"), revisions.map(MemoRevision::memoContent))
            assertEquals(2, store.revisionCountForMemo("memo-pruned"))
            assertEquals(2, store.blobCount())
            assertEquals(2, blobFilesUnder(blobRoot).size)
        }

    @Test
    fun `appendLocalRevision reuses indexed asset fingerprint instead of reloading historical candidate assets`() =
        runTest {
            val fingerprintStore = HistoricalAssetLookupFailingMemoVersionStore()
            val fingerprintJournal =
                MemoVersionJournal(
                    store = fingerprintStore,
                    blobRoot = blobRoot,
                    markdownStorageDataSource = markdownStorageDataSource,
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
            assertEquals(listOf("beta", versionA.content), revisions.map(MemoRevision::memoContent))
            assertEquals(2, fingerprintStore.revisionCountForMemo(versionA.id))
        }

    @Test
    fun `appendLocalRevision skips recording when memo snapshots are disabled`() =
        runTest {
            val disabledJournal =
                MemoVersionJournal(
                    store = store,
                    blobRoot = blobRoot,
                    markdownStorageDataSource = markdownStorageDataSource,
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

            assertTrue(disabledJournal.listMemoRevisions(memo = memo, cursor = null, limit = 10).items.isEmpty())
            assertEquals(0, store.revisionCountForMemo(memo.id))
        }

    @Test
    fun `appendLocalRevision prunes revisions older than configured retention age`() =
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
                    markdownStorageDataSource = markdownStorageDataSource,
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

            assertEquals(listOf("v3", "v2"), revisions.map(MemoRevision::memoContent))
            assertEquals(2, store.revisionCountForMemo("memo-aged"))
        }

    @Test
    fun `appendLocalRevision prunes with targeted stale lookup instead of loading every revision`() =
        runTest {
            val pruningStore = FullHistoryLookupFailingMemoVersionStore()
            val pruningJournal =
                MemoVersionJournal(
                    store = pruningStore,
                    blobRoot = blobRoot,
                    markdownStorageDataSource = markdownStorageDataSource,
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
            assertEquals(listOf("v3", "v2"), revisions.map(MemoRevision::memoContent))
            assertEquals(2, pruningStore.revisionCountForMemo(original.id))
        }

    @Test
    fun `clearAllMemoSnapshots removes revision history and blob files`() =
        runTest {
            val memo = memo(id = "memo-clear", content = "clear me", rawContent = "- 09:00 clear me")

            journal.appendLocalRevision(
                memo = memo,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_CREATE,
            )

            journal.clearAllMemoSnapshots()

            assertTrue(journal.listMemoRevisions(memo = memo, cursor = null, limit = 10).items.isEmpty())
            assertEquals(0, store.blobCount())
            assertTrue(blobFilesUnder(blobRoot).isEmpty())
        }

    @Test
    fun `appendLocalRevision rolls back partial version rows and blobs when revision asset write fails`() =
        runTest {
            val failingStore = ReplaceAssetsFailingMemoVersionStore()
            val transactionalJournal =
                MemoVersionJournal(
                    store = failingStore,
                    blobRoot = blobRoot,
                    markdownStorageDataSource = markdownStorageDataSource,
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

            assertTrue(failure is IOException)
            assertEquals(0, failingStore.revisionCountForMemo(memo.id))
            assertEquals(0, failingStore.blobCount())
            assertTrue(blobFilesUnder(blobRoot).isEmpty())
        }

    @Test
    fun `restoreMemoRevision aborts without recording restore revision when attachment restore fails`() =
        runTest {
            val failingMediaAccess = FailingJournalWorkspaceMediaAccess()
            val failingJournal =
                MemoVersionJournal(
                    store = store,
                    blobRoot = blobRoot,
                    markdownStorageDataSource = markdownStorageDataSource,
                    workspaceMediaAccess = failingMediaAccess,
                    memoTextProcessor = textProcessor,
                    now = { Instant.parse("2026-03-27T09:00:00Z").toEpochMilli() },
                    nextCommitId = store::nextCommitId,
                    nextRevisionId = store::nextRevisionId,
                    nextBatchId = store::nextBatchId,
                )
            val original =
                memo(
                    id = "memo-restore-fail",
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
            markdownStorageDataSource.mainFiles["2026_03_27.md"] = updated.rawContent
            failingMediaAccess.imageFiles["img_before.png"] = "before-image".toByteArray()

            failingJournal.appendLocalRevision(
                memo = original,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_CREATE,
            )
            failingMediaAccess.imageFiles.clear()
            failingJournal.appendLocalRevision(
                memo = updated,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_EDIT,
            )
            val originalRevisionId =
                failingJournal
                    .listMemoRevisions(memo = updated, cursor = null, limit = 10)
                    .items
                    .last()
                    .revisionId

            val failure =
                runCatching {
                    failingJournal.restoreMemoRevision(
                        currentMemo = updated,
                        revisionId = originalRevisionId,
                    )
                }.exceptionOrNull()

            assertTrue(failure is IOException)
            val revisions = failingJournal.listMemoRevisions(memo = updated, cursor = null, limit = 10).items
            assertEquals(listOf("after", original.content), revisions.map(MemoRevision::memoContent))
        }

    @Test
    fun `restoreMemoRevision rolls back attachments and markdown when markdown write fails after attachment restore`() =
        runTest {
            val failingStorage = FailingJournalMarkdownStorageDataSource()
            val rollbackMediaAccess = JournalWorkspaceMediaAccess()
            val rollbackJournal =
                MemoVersionJournal(
                    store = store,
                    blobRoot = blobRoot,
                    markdownStorageDataSource = failingStorage,
                    workspaceMediaAccess = rollbackMediaAccess,
                    memoTextProcessor = textProcessor,
                    now = { Instant.parse("2026-03-27T09:00:00Z").toEpochMilli() },
                    nextCommitId = store::nextCommitId,
                    nextRevisionId = store::nextRevisionId,
                    nextBatchId = store::nextBatchId,
                )
            val original =
                memo(
                    id = "memo-rollback-fail",
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
            failingStorage.mainFiles["2026_03_27.md"] = updated.rawContent
            rollbackMediaAccess.imageFiles["img_before.png"] = "stale-image".toByteArray()

            rollbackMediaAccess.imageFiles["img_before.png"] = "before-image".toByteArray()
            rollbackJournal.appendLocalRevision(
                memo = original,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_CREATE,
            )
            rollbackMediaAccess.imageFiles["img_before.png"] = "stale-image".toByteArray()
            rollbackJournal.appendLocalRevision(
                memo = updated,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_EDIT,
            )
            val originalRevisionId =
                rollbackJournal
                    .listMemoRevisions(memo = updated, cursor = null, limit = 10)
                    .items
                    .last()
                    .revisionId

            failingStorage.failOnSave = true
            val failure =
                runCatching {
                    rollbackJournal.restoreMemoRevision(
                        currentMemo = updated,
                        revisionId = originalRevisionId,
                    )
                }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertEquals(updated.rawContent, failingStorage.mainFiles.getValue("2026_03_27.md"))
            assertEquals("stale-image", rollbackMediaAccess.imageFiles.getValue("img_before.png").decodeToString())
            val revisions = rollbackJournal.listMemoRevisions(memo = updated, cursor = null, limit = 10).items
            assertEquals(listOf("after", original.content), revisions.map(MemoRevision::memoContent))
        }

    @Test
    fun `restoreMemoRevision reuses stored revision assets for restore history append when workspace reads are unavailable`() =
        runTest {
            val unreadableAfterRestoreMediaAccess = UnreadableAfterRestoreWorkspaceMediaAccess()
            val restoreReuseJournal =
                MemoVersionJournal(
                    store = store,
                    blobRoot = blobRoot,
                    markdownStorageDataSource = markdownStorageDataSource,
                    workspaceMediaAccess = unreadableAfterRestoreMediaAccess,
                    memoTextProcessor = textProcessor,
                    now = { Instant.parse("2026-03-27T09:00:00Z").toEpochMilli() },
                    nextCommitId = store::nextCommitId,
                    nextRevisionId = store::nextRevisionId,
                    nextBatchId = store::nextBatchId,
                )
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
            markdownStorageDataSource.mainFiles["2026_03_27.md"] = updated.rawContent
            unreadableAfterRestoreMediaAccess.imageFiles["img_before.png"] = "before-image".toByteArray()

            restoreReuseJournal.appendLocalRevision(
                memo = original,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_CREATE,
            )
            unreadableAfterRestoreMediaAccess.imageFiles.clear()
            restoreReuseJournal.appendLocalRevision(
                memo = updated,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                origin = MemoRevisionOrigin.LOCAL_EDIT,
            )
            unreadableAfterRestoreMediaAccess.hideReads = true
            val originalRevisionId =
                restoreReuseJournal
                    .listMemoRevisions(memo = updated, cursor = null, limit = 10)
                    .items
                    .last()
                    .revisionId

            restoreReuseJournal.restoreMemoRevision(
                currentMemo = updated,
                revisionId = originalRevisionId,
            )

            val revisions = restoreReuseJournal.listMemoRevisions(memo = original, cursor = null, limit = 10).items
            assertEquals(listOf("after", original.content), revisions.map(MemoRevision::memoContent))
            assertEquals(2, store.revisionCountForMemo(original.id))
            assertTrue(unreadableAfterRestoreMediaAccess.imageFiles.containsKey("img_before.png"))
        }
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

private open class JournalMarkdownStorageDataSource : MarkdownStorageDataSource {
    val mainFiles = linkedMapOf<String, String>()
    val trashFiles = linkedMapOf<String, String>()

    override suspend fun listFilesIn(
        directory: MemoDirectoryType,
        targetFilename: String?,
    ) = emptyList<com.lomo.data.source.FileContent>()

    override suspend fun listMetadataIn(directory: MemoDirectoryType) = emptyList<com.lomo.data.source.FileMetadata>()

    override suspend fun listMetadataWithIdsIn(directory: MemoDirectoryType) =
        emptyList<com.lomo.data.source.FileMetadataWithId>()

    override suspend fun readFileByDocumentIdIn(
        directory: MemoDirectoryType,
        documentId: String,
    ): String? = null

    override suspend fun readFileIn(
        directory: MemoDirectoryType,
        filename: String,
    ): String? =
        when (directory) {
            MemoDirectoryType.MAIN -> mainFiles[filename]
            MemoDirectoryType.TRASH -> trashFiles[filename]
        }

    override suspend fun readFile(uri: android.net.Uri): String? = null

    open override suspend fun saveFileIn(
        directory: MemoDirectoryType,
        filename: String,
        content: String,
        append: Boolean,
        uri: android.net.Uri?,
    ): String? {
        val target =
            when (directory) {
                MemoDirectoryType.MAIN -> mainFiles
                MemoDirectoryType.TRASH -> trashFiles
            }
        val existing = target[filename]
        target[filename] =
            if (append && !existing.isNullOrEmpty()) {
                existing + "\n" + content
            } else {
                content
            }
        return null
    }

    override suspend fun deleteFileIn(
        directory: MemoDirectoryType,
        filename: String,
        uri: android.net.Uri?,
    ) {
        when (directory) {
            MemoDirectoryType.MAIN -> mainFiles.remove(filename)
            MemoDirectoryType.TRASH -> trashFiles.remove(filename)
        }
    }

    override suspend fun getFileMetadataIn(
        directory: MemoDirectoryType,
        filename: String,
    ): com.lomo.data.source.FileMetadata? = null
}

private class FailingJournalMarkdownStorageDataSource : JournalMarkdownStorageDataSource() {
    var failOnSave: Boolean = false

    override suspend fun saveFileIn(
        directory: MemoDirectoryType,
        filename: String,
        content: String,
        append: Boolean,
        uri: android.net.Uri?,
    ): String? {
        if (failOnSave) {
            throw IOException("boom-save: $directory/$filename")
        }
        return super.saveFileIn(directory, filename, content, append, uri)
    }
}

private open class JournalWorkspaceMediaAccess : WorkspaceMediaAccess {
    val imageFiles = linkedMapOf<String, ByteArray>()
    val voiceFiles = linkedMapOf<String, ByteArray>()

    override suspend fun listFiles(category: WorkspaceMediaCategory): List<WorkspaceMediaFile> =
        currentMap(category).map { (filename, bytes) ->
            WorkspaceMediaFile(filename = filename, bytes = bytes)
        }

    override suspend fun listFilenames(category: WorkspaceMediaCategory): List<String> =
        currentMap(category).keys.sorted()

    override suspend fun writeFile(
        category: WorkspaceMediaCategory,
        filename: String,
        bytes: ByteArray,
    ) {
        currentMap(category)[filename] = bytes
    }

    override suspend fun deleteFile(
        category: WorkspaceMediaCategory,
        filename: String,
    ) {
        currentMap(category).remove(filename)
    }

    override suspend fun readFileBytes(
        category: WorkspaceMediaCategory,
        filename: String,
    ): ByteArray? = currentMap(category)[filename]

    private fun currentMap(category: WorkspaceMediaCategory): LinkedHashMap<String, ByteArray> =
        when (category) {
            WorkspaceMediaCategory.IMAGE -> imageFiles
            WorkspaceMediaCategory.VOICE -> voiceFiles
        }
}

private class FailingJournalWorkspaceMediaAccess : JournalWorkspaceMediaAccess() {
    override suspend fun writeFile(
        category: WorkspaceMediaCategory,
        filename: String,
        bytes: ByteArray,
    ) {
        throw IOException("boom: $filename")
    }
}

private class UnreadableAfterRestoreWorkspaceMediaAccess : JournalWorkspaceMediaAccess() {
    var hideReads: Boolean = false

    override suspend fun readFileBytes(
        category: WorkspaceMediaCategory,
        filename: String,
    ): ByteArray? =
        if (hideReads) {
            null
        } else {
            super.readFileBytes(category, filename)
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
