package com.lomo.data.engine.store

/*
 * Behavior Contract:
 * - Unit under test: BoltFfiStorePort (production StorePort mapping over StoreNativeBridge).
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: map domain StorePort requests/results to/from native bridge DTOs; blank operationId
 *   is filled only when no pendingPromotes; promotes require non-blank matching operationId (D4);
 *   null getMemo remains null; command kinds map bijectively.
 *
 * Scenarios:
 * - Given a bridge page with one summary and next cursor, when queryMemos runs, then filters/search
 *   are forwarded and domain page fields are mapped (incl. ULong→Long revisions).
 * - Given bridge getMemo returns null, when getMemo runs, then null is observed.
 * - Given bridge getMemo returns a snapshot, when getMemo runs, then body and summary map.
 * - Given each StoreMemoCommandKind, when applyMemoCommand runs, then bridge receives the matching
 *   kind and typed invalidation scopes map.
 * - Given an unknown native invalidation scope, when a commit crosses the bridge, then it is
 *   rejected rather than treated as a broad or empty refresh.
 * - Given blank operationId and empty pendingPromotes, when applyMemoCommand runs, then a non-blank
 *   operationId is minted for the memo-only command.
 * - Given blank operationId with non-empty pendingPromotes, when applyMemoCommand runs, then fail
 *   closed without calling the bridge (no UUID mint under promote).
 * - Given a rebuild result, when startRebuild runs, then counters map to domain longs.
 * - Given the engine refuses a memo command, query, get or rebuild, when the call crosses the
 *   boundary, then an EngineCommandFailureException carries the typed category/code/retry/ids and a
 *   non-blank message instead of the message-less native carrier.
 * - Given an engine vocabulary this build does not know, when a rejection crosses the boundary, then
 *   the original code and diagnostic are preserved rather than replaced by a parse failure.
 *
 * Observable outcomes: domain StoreMemoPage / Snapshot / Commit / RebuildResult; last bridge
 * request fields.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=data --include-classes='com.lomo.data.engine.store.BoltFfiStorePortTest'
 * - RED: BoltFfiStorePort untested / zero-hit under coverage before this host contract.
 *
 * Excludes:
 * - Real BoltFFI/JNI handle lifecycle (device-smoke / native contracts).
 *
 * Test Change Justification:
 * - Reason category: production media promote wiring on store memo commands (stage-4 cutover).
 * - Old behavior/assertion being replaced: blank operationId always minted; no pendingPromotes field.
 * - Why old assertion is no longer correct: promotes must share a non-blank caller operation-id;
 *   minting under promote would dual-author identity and break same-operation atomicity.
 * - Coverage preserved by: page/get/rebuild mapping and memo-only blank-id mint scenarios remain.
 * - Why this is not fitting the test to the implementation: locks the D4 operation-id boundary, not
 *   internal UUID helper details.
 */

import com.lomo.nativebridge.StoreMemoCommand as BridgeMemoCommand
import com.lomo.nativebridge.StoreMemoCommandKind as BridgeMemoCommandKind
import com.lomo.nativebridge.StoreMemoCommit as BridgeMemoCommit
import com.lomo.nativebridge.StoreMemoPage as BridgeMemoPage
import com.lomo.nativebridge.StoreMemoQuery as BridgeMemoQuery
import com.lomo.nativebridge.StoreMemoSnapshot as BridgeMemoSnapshot
import com.lomo.nativebridge.StoreMemoSummary as BridgeMemoSummary
import com.lomo.nativebridge.StorePageCursor as BridgePageCursor
import com.lomo.nativebridge.StoreRebuildResult as BridgeRebuildResult
import com.lomo.data.engine.media.MediaPromotePlan
import com.lomo.data.engine.media.MediaStagedFacts
import com.lomo.domain.model.EngineCommandFailureException
import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineRetryDisposition
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank

private class RecordingStoreNativeBridge : StoreNativeBridge {
    /** When set, every bridge call refuses exactly as the generated BoltFFI carrier does. */
    var failure: com.lomo.nativebridge.EngineError.Failure? = null
    var lastQuery: BridgeMemoQuery? = null
    var lastCursor: BridgePageCursor? = null
    var lastPageSize: UInt? = null
    var lastGetMemoId: String? = null
    var lastCommand: BridgeMemoCommand? = null
    var lastRebuildBatch: UInt? = null

    var page: BridgeMemoPage =
        BridgeMemoPage(
            items = emptyList(),
            nextCursor = null,
            highWaterRevision = 0uL,
            queryFingerprint = "fp",
        )
    var snapshot: BridgeMemoSnapshot? = null
    var sidebar =
        com.lomo.nativebridge.StoreSidebarProjection(
            schemaVersion = 1u,
            memoCount = 0L,
            dateCounts = emptyList(),
            tagCounts = emptyList(),
        )
    var commit: BridgeMemoCommit =
        BridgeMemoCommit(
            operationId = "op",
            memoId = "m1",
            coreRevision = 1uL,
            eventSequence = 2uL,
            contentRevision = 3uL,
            fileFingerprint = "ff",
            scopes = listOf("memo_list"),
            idempotentReplay = false,
        )
    var rebuild: BridgeRebuildResult =
        BridgeRebuildResult(
            memosIndexed = 4uL,
            fileCount = 4uL,
            attachmentCount = 0uL,
            workspaceDigest = "ws",
            storeDigest = "ws",
            corruptLomoIsolated = 1uL,
            highWaterRevision = 9uL,
        )

    override fun queryMemos(
        query: BridgeMemoQuery,
        cursor: BridgePageCursor?,
        pageSize: UInt,
    ): BridgeMemoPage {
        lastQuery = query
        lastCursor = cursor
        lastPageSize = pageSize
        failure?.let { throw it }
        return page
    }

    override fun listHistoryAttachmentRefs(): List<com.lomo.nativebridge.StoreHistoryAttachmentRef> =
        emptyList()

    override fun getMemo(memoId: String): BridgeMemoSnapshot? {
        lastGetMemoId = memoId
        failure?.let { throw it }
        return snapshot
    }

    override fun sourceDocumentFingerprint(sourcePath: String): String? = null

    override fun sidebarProjection(): com.lomo.nativebridge.StoreSidebarProjection = sidebar

    override fun applyMemoCommand(command: BridgeMemoCommand): BridgeMemoCommit {
        lastCommand = command
        failure?.let { throw it }
        return commit
    }

    override fun commitSafProjectionMutation(
        command: BridgeMemoCommand,
        projection: com.lomo.nativebridge.StoreSafMemoProjection?,
    ): BridgeMemoCommit = error("SAF projection commit not expected")

    override fun startRebuild(batchSize: UInt): BridgeRebuildResult {
        lastRebuildBatch = batchSize
        failure?.let { throw it }
        return rebuild
    }
}

private fun bridgeSummary(
    id: String = "m1",
    preview: String = "hello",
): BridgeMemoSummary =
    BridgeMemoSummary(
        memoId = id,
        sourcePath = "memos/2026_01_01.md",
        fileFingerprint = "fp1",
        updatedAtMs = 20L,
        createdAtMs = 10L,
        hasTodo = true,
        hasUrl = false,
        hasAttachment = true,
        isPinned = true,
        isTrashed = false,
        bodyPreview = preview,
        contentRevision = 7uL,
        rank = 1.5,
        tags = listOf("work"),
        imageUrls = listOf("images/a.png"),
        reminders = emptyList(),
    )

class BoltFfiStorePortTest : FunSpec({
    test("queryMemos forwards filters and maps page to domain types") {
        val bridge =
            RecordingStoreNativeBridge().apply {
                page =
                    BridgeMemoPage(
                        items = listOf(bridgeSummary()),
                        nextCursor = BridgePageCursor("c2"),
                        highWaterRevision = 11uL,
                        queryFingerprint = "q-fp",
                    )
            }
        val port = BoltFfiStorePort(bridge)

        val result =
            port.queryMemos(
                StoreMemoQuery(
                    searchText = "hi",
                    filters =
                        StoreMemoFilters(
                            tag = "work",
                            tagSubtree = true,
                            dateFromInclusiveMs = 1L,
                            dateUntilExclusiveMs = 2L,
                            hasTodo = true,
                            hasAttachment = true,
                            hasUrl = false,
                            pinnedOnly = true,
                            includeTrash = false,
                            trashOnly = false,
                        ),
                ),
                cursor = StorePageCursor("c1"),
                pageSize = 30,
            )

        bridge.lastPageSize shouldBe 30u
        bridge.lastCursor?.encoded shouldBe "c1"
        bridge.lastQuery?.searchText shouldBe "hi"
        bridge.lastQuery?.filters?.tag shouldBe "work"
        bridge.lastQuery?.filters?.tagSubtree shouldBe true
        bridge.lastQuery?.filters?.hasTodo shouldBe true
        bridge.lastQuery?.filters?.pinnedOnly shouldBe true
        result.items.size shouldBe 1
        result.items[0].memoId shouldBe "m1"
        result.items[0].contentRevision shouldBe 7L
        result.items[0].rank shouldBe 1.5
        result.items[0].hasTodo shouldBe true
        result.items[0].isPinned shouldBe true
        result.nextCursor?.encoded shouldBe "c2"
        result.highWaterRevision shouldBe 11L
        result.queryFingerprint shouldBe "q-fp"
    }

    test("getMemo returns null when bridge has no snapshot") {
        val bridge = RecordingStoreNativeBridge().apply { snapshot = null }
        BoltFfiStorePort(bridge).getMemo("missing").shouldBeNull()
        bridge.lastGetMemoId shouldBe "missing"
    }

    test("getMemo maps snapshot body and summary") {
        val bridge =
            RecordingStoreNativeBridge().apply {
                snapshot = BridgeMemoSnapshot(summary = bridgeSummary(preview = "prev"), body = "full body")
            }
        val snap = BoltFfiStorePort(bridge).getMemo("m1")
        snap.shouldNotBeNull()
        snap.body shouldBe "full body"
        snap.summary.memoId shouldBe "m1"
        snap.summary.bodyPreview shouldBe "prev"
        snap.summary.contentRevision shouldBe 7L
    }

    test("sidebarProjection validates schema and maps aggregate counts") {
        val bridge = RecordingStoreNativeBridge()
        bridge.sidebar =
            com.lomo.nativebridge.StoreSidebarProjection(
                schemaVersion = 1u,
                memoCount = 2_001L,
                dateCounts = listOf(com.lomo.nativebridge.StoreSidebarDateCount("2026-08-04", 2_001L)),
                tagCounts = listOf(com.lomo.nativebridge.StoreSidebarTagCount("all", 2_001L)),
            )

        val projection = BoltFfiStorePort(bridge).sidebarProjection()

        projection.memoCount shouldBe 2_001
        projection.dateCounts.single().count shouldBe 2_001
        projection.tagCounts.single().name shouldBe "all"
    }

    test("applyMemoCommand maps every command kind to bridge enum") {
        val bridge = RecordingStoreNativeBridge()
        val port = BoltFfiStorePort(bridge)
        val kinds =
            listOf(
                StoreMemoCommandKind.Create to BridgeMemoCommandKind.CREATE,
                StoreMemoCommandKind.Update to BridgeMemoCommandKind.UPDATE,
                StoreMemoCommandKind.Delete to BridgeMemoCommandKind.DELETE,
                StoreMemoCommandKind.Restore to BridgeMemoCommandKind.RESTORE,
                StoreMemoCommandKind.Pin to BridgeMemoCommandKind.PIN,
                StoreMemoCommandKind.Unpin to BridgeMemoCommandKind.UNPIN,
                StoreMemoCommandKind.HistoryRestore to BridgeMemoCommandKind.HISTORY_RESTORE,
            )
        for ((domainKind, bridgeKind) in kinds) {
            bridge.commit =
                BridgeMemoCommit(
                    operationId = "op-$domainKind",
                    memoId = "m-x",
                    coreRevision = 1uL,
                    eventSequence = 2uL,
                    contentRevision = 3uL,
                    fileFingerprint = "ff",
                    scopes = listOf("search"),
                    idempotentReplay = true,
                )
            val commit =
                port.applyMemoCommand(
                    StoreMemoCommand(
                        operationId = "op-$domainKind",
                        kind = domainKind,
                        memoId = "m-x",
                        expectedRevision = 3L,
                        expectedFingerprint = "ff",
                        content = "body",
                        tags = listOf("t"),
                        pin = true,
                    ),
                )
            bridge.lastCommand?.kind shouldBe bridgeKind
            commit.operationId shouldBe "op-$domainKind"
            commit.memoId shouldBe "m-x"
            commit.coreRevision shouldBe 1L
            commit.eventSequence shouldBe 2L
            commit.contentRevision shouldBe 3L
            commit.fileFingerprint shouldBe "ff"
            commit.scopes shouldBe listOf(StoreInvalidationScope.Search)
            commit.idempotentReplay shouldBe true
        }
    }

    test("unknown native invalidation scope fails closed at the bridge edge") {
        val bridge =
            RecordingStoreNativeBridge().apply {
                commit = commit.copy(scopes = listOf("memo:m1"))
            }

        val error =
            shouldThrow<IllegalStateException> {
                BoltFfiStorePort(bridge).applyMemoCommand(
                    StoreMemoCommand(
                        operationId = "op-unknown-scope",
                        kind = StoreMemoCommandKind.Update,
                        memoId = "m1",
                        expectedRevision = 1L,
                    ),
                )
            }

        error.message shouldContain "Unknown Rust store invalidation scope"
    }

    test("blank operationId without promotes is replaced before bridge apply") {
        val bridge = RecordingStoreNativeBridge()
        BoltFfiStorePort(bridge).applyMemoCommand(
            StoreMemoCommand(
                operationId = "  ",
                kind = StoreMemoCommandKind.Create,
                memoId = "",
                expectedRevision = 0L,
                content = "x",
            ),
        )
        bridge.lastCommand?.operationId.shouldNotBeNull().shouldNotBeBlank()
    }

    test("blank operationId with pendingPromotes fails closed without minting UUID") {
        val bridge = RecordingStoreNativeBridge()
        val plan =
            MediaPromotePlan(
                operationId = "  ",
                staged =
                    MediaStagedFacts(
                        digest = "d".repeat(64),
                        size = 1L,
                        mime = "image/png",
                        stagingPath = "/tmp/stage",
                        humanNameHint = "a.png",
                        suggestedFinalRelativePath = "media/a.png",
                    ),
                finalRelativePath = "media/a.png",
            )
        val error =
            shouldThrow<IllegalStateException> {
                BoltFfiStorePort(bridge).applyMemoCommand(
                    StoreMemoCommand(
                        operationId = "",
                        kind = StoreMemoCommandKind.Create,
                        memoId = "",
                        expectedRevision = 0L,
                        content = "![i](media/a.png)",
                        pendingPromotes = listOf(plan),
                    ),
                )
            }
        error.message.shouldNotBeNull().shouldContain("non-blank operationId")
        bridge.lastCommand.shouldBeNull()
    }

    test("startRebuild maps counters digests and batch size") {
        val bridge =
            RecordingStoreNativeBridge().apply {
                rebuild =
                    BridgeRebuildResult(
                        memosIndexed = 12uL,
                        fileCount = 12uL,
                        attachmentCount = 3uL,
                        workspaceDigest = "digest-a",
                        storeDigest = "digest-a",
                        corruptLomoIsolated = 2uL,
                        highWaterRevision = 99uL,
                    )
            }
        val result = BoltFfiStorePort(bridge).startRebuild(batchSize = 64)
        bridge.lastRebuildBatch shouldBe 64u
        result.memosIndexed shouldBe 12L
        result.fileCount shouldBe 12L
        result.attachmentCount shouldBe 3L
        result.workspaceDigest shouldBe "digest-a"
        result.storeDigest shouldBe "digest-a"
        result.corruptLomoIsolated shouldBe 2L
        result.highWaterRevision shouldBe 99L
    }

    test("queryMemos maps tags and image urls from bridge summary") {
        val bridge =
            RecordingStoreNativeBridge().apply {
                page =
                    BridgeMemoPage(
                        items = listOf(bridgeSummary()),
                        nextCursor = null,
                        highWaterRevision = 1uL,
                        queryFingerprint = "fp",
                    )
            }
        val page = BoltFfiStorePort(bridge).queryMemos(StoreMemoQuery(), null, 10)
        page.items.single().tags shouldBe listOf("work")
        page.items.single().imageUrls shouldBe listOf("images/a.png")
    }

    test("given the engine refuses a memo command then the typed rejection survives the boundary") {
        val bridge =
            RecordingStoreNativeBridge().apply {
                failure =
                    com.lomo.nativebridge.EngineError.Failure(
                        com.lomo.nativebridge.EngineFailure(
                            category = "conflict",
                            code = "stale_snapshot",
                            retryDisposition = "after_user_action",
                            operationId = "op-9",
                            jobId = null,
                            diagnostic = "memo changed before the mutation began",
                        ),
                    )
            }

        val error =
            shouldThrow<EngineCommandFailureException> {
                BoltFfiStorePort(bridge).applyMemoCommand(
                    StoreMemoCommand(
                        operationId = "op-9",
                        kind = StoreMemoCommandKind.Delete,
                        memoId = "m1",
                        expectedRevision = 3L,
                        expectedFingerprint = "ff",
                    ),
                )
            }

        error.failure.code shouldBe "stale_snapshot"
        error.failure.category shouldBe EngineFailureCategory.CONFLICT
        error.failure.retryDisposition shouldBe EngineRetryDisposition.AFTER_USER_ACTION
        error.failure.operationId shouldBe "op-9"
        error.message.orEmpty().shouldNotBeBlank()
        error.message.orEmpty() shouldContain "stale_snapshot"
        error.message.orEmpty() shouldContain "memo changed before the mutation began"
    }

    test("given the engine refuses a query or rebuild then the rejection is typed too") {
        val failure =
            com.lomo.nativebridge.EngineError.Failure(
                com.lomo.nativebridge.EngineFailure(
                    category = "storage",
                    code = "sqlite_error",
                    retryDisposition = "never",
                    operationId = null,
                    jobId = "job-2",
                    diagnostic = "disk I/O error",
                ),
            )

        shouldThrow<EngineCommandFailureException> {
            BoltFfiStorePort(RecordingStoreNativeBridge().apply { this.failure = failure })
                .queryMemos(StoreMemoQuery(), null, 10)
        }.failure.code shouldBe "sqlite_error"

        shouldThrow<EngineCommandFailureException> {
            BoltFfiStorePort(RecordingStoreNativeBridge().apply { this.failure = failure })
                .getMemo("m1")
        }.failure.jobId shouldBe "job-2"

        shouldThrow<EngineCommandFailureException> {
            BoltFfiStorePort(RecordingStoreNativeBridge().apply { this.failure = failure })
                .startRebuild(batchSize = 64)
        }.failure.category shouldBe EngineFailureCategory.STORAGE
    }

    test("given an unrecognized engine vocabulary then the original rejection is still preserved") {
        val bridge =
            RecordingStoreNativeBridge().apply {
                failure =
                    com.lomo.nativebridge.EngineError.Failure(
                        com.lomo.nativebridge.EngineFailure(
                            category = "teapot",
                            code = "trash_marker_missing",
                            retryDisposition = "someday",
                            operationId = null,
                            jobId = null,
                            diagnostic = "durable trash marker was absent",
                        ),
                    )
            }

        val error =
            shouldThrow<EngineCommandFailureException> {
                BoltFfiStorePort(bridge).startRebuild(batchSize = 64)
            }

        error.failure.code shouldBe "trash_marker_missing"
        error.failure.category shouldBe EngineFailureCategory.INTERNAL
        error.message.orEmpty() shouldContain "durable trash marker was absent"
        error.message.orEmpty() shouldContain "teapot"
    }
})
