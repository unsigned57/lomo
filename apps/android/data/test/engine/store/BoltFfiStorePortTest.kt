package com.lomo.data.engine.store

/*
 * Behavior Contract:
 * - Unit under test: BoltFfiStorePort (production StorePort mapping over StoreNativeBridge).
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: map domain StorePort requests/results to/from native session and store bridges;
 *   memo writes without staged media go through session FFI; blank operationId is filled only when
 *   no pendingPromotes; promotes require non-blank matching operationId (D4) and ride the same
 *   session create/update PlannedFile batch; null getMemo remains
 *   null.
 *
 * Scenarios:
 * - Given a bridge page with one summary and next cursor, when queryMemos runs, then filters/search
 *   are forwarded and domain page fields are mapped (incl. ULong→Long revisions).
 * - Given bridge getMemo returns null, when getMemo runs, then null is observed.
 * - Given bridge getMemo returns a snapshot, when getMemo runs, then body and summary map.
 * - Given each StoreMemoCommandKind without staged media, when applyMemoCommand runs, then the
 *   matching session FFI request is recorded and typed invalidation scopes map.
 * - Given an unknown native invalidation scope, when a commit crosses the bridge, then it is
 *   rejected rather than treated as a broad or empty refresh.
 * - Given blank operationId and empty pendingPromotes, when applyMemoCommand runs, then a non-blank
 *   operationId is minted for the session create.
 * - Given blank operationId with non-empty pendingPromotes, when applyMemoCommand runs, then fail
 *   closed without calling the bridge (no UUID mint under promote).
 * - Given create with matching pendingPromotes, when applyMemoCommand runs, then session create
 *   receives those plans and store applyMemoCommand is not called.
 * - Given a permanent-delete batch, when permanentDeleteMany runs, then each target is deleted
 *   through session FFI and store batch delete is not called.
 * - Given a rebuild result, when startRebuild runs, then counters map to domain longs.
 * - Given a Rust reminder plan, when queryReminderPlan runs, then the complete session/zone
 *   request crosses the bridge and planned alarms map back without changing identity or generation.
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
 * - Reason category: production media promote wiring on session memo commands.
 * - Old behavior/assertion being replaced: staged promotes shared the store Direct writer.
 * - Why old assertion is no longer correct: WorkspaceSession owns document writes and attachment
 *   PlannedFiles in one batch; store applyMemoCommand is not a write path for create/update.
 * - Coverage preserved by: page/get/rebuild mapping and memo-only blank-id mint scenarios remain.
 * - Why this is not fitting the test to the implementation: locks the D4 operation-id boundary and
 *   the session PlannedFile promote handoff, not internal UUID helper details.
 */

import com.lomo.nativebridge.StoreMemoCommand as BridgeMemoCommand
import com.lomo.nativebridge.StoreMemoCommit as BridgeMemoCommit
import com.lomo.nativebridge.StoreMemoPage as BridgeMemoPage
import com.lomo.nativebridge.StoreMemoQuery as BridgeMemoQuery
import com.lomo.nativebridge.StoreMemoSnapshot as BridgeMemoSnapshot
import com.lomo.nativebridge.StoreMemoSummary as BridgeMemoSummary
import com.lomo.nativebridge.StorePageCursor as BridgePageCursor
import com.lomo.nativebridge.StoreRebuildResult as BridgeRebuildResult
import com.lomo.nativebridge.SessionCreateMemoRequest
import com.lomo.nativebridge.SessionDeleteMemoRequest
import com.lomo.nativebridge.SessionPinMemoRequest
import com.lomo.nativebridge.SessionRestoreRequest
import com.lomo.nativebridge.SessionRestoreResult
import com.lomo.nativebridge.SessionRestoreRevisionRequest
import com.lomo.nativebridge.SessionUpdateMemoRequest
import com.lomo.data.engine.SessionNativeBridge
import com.lomo.data.engine.media.MediaPromotePlan
import com.lomo.data.engine.media.MediaStagedFacts
import com.lomo.data.engine.store.StorePlannedAlarm
import com.lomo.data.engine.store.StoreReminderQuery
import com.lomo.data.engine.store.StoreReminderSession
import com.lomo.data.engine.store.StoreTimeZoneContext
import com.lomo.data.engine.store.StoreZoneTransition
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

private class RecordingStoreNativeBridge : StoreNativeBridge, SessionNativeBridge {
    /** When set, every bridge call refuses exactly as the generated BoltFFI carrier does. */
    var failure: com.lomo.nativebridge.EngineError.Failure? = null
    var lastQuery: BridgeMemoQuery? = null
    var lastCursor: BridgePageCursor? = null
    var lastPageSize: UInt? = null
    var lastGetMemoId: String? = null
    var lastCommand: BridgeMemoCommand? = null
    var lastSessionCreate: SessionCreateMemoRequest? = null
    var lastSessionUpdate: SessionUpdateMemoRequest? = null
    var lastSessionDelete: SessionDeleteMemoRequest? = null
    var lastSessionPin: SessionPinMemoRequest? = null
    var lastSessionRestore: SessionRestoreRequest? = null
    var lastSessionRestoreRevision: SessionRestoreRevisionRequest? = null
    var lastSessionPermanentDelete: SessionRestoreRequest? = null
    val sessionPermanentDeletes = mutableListOf<SessionRestoreRequest>()
    var lastRebuildBatch: UInt? = null
    var lastReminderQuery: com.lomo.nativebridge.StoreReminderQuery? = null

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

    override fun queryCount(query: BridgeMemoQuery): ULong {
        failure?.let { throw it }
        return 0uL
    }

    override fun selectMemoPromotePlans(
        content: String,
        candidates: List<com.lomo.nativebridge.MediaPromotePlanDto>,
    ): List<com.lomo.nativebridge.MediaPromotePlanDto> {
        failure?.let { throw it }
        return candidates
    }

    override fun memoStatisticsRows(): List<com.lomo.nativebridge.StoreMemoStatisticsRow> {
        failure?.let { throw it }
        return emptyList()
    }

    override fun listHistoryAttachmentRefs(): List<com.lomo.nativebridge.StoreHistoryAttachmentRef> =
        emptyList()

    override fun listMemoHistory(
        memoId: String,
        cursor: String?,
        limit: UInt,
    ): com.lomo.nativebridge.StoreMemoHistoryPage =
        com.lomo.nativebridge.StoreMemoHistoryPage(items = emptyList(), nextCursor = null)

    override fun getMemo(memoId: String): BridgeMemoSnapshot? {
        lastGetMemoId = memoId
        failure?.let { throw it }
        return snapshot
    }

    override fun sourceDocumentFingerprint(sourcePath: String): String? = null

    var reminderPlan: com.lomo.nativebridge.StoreReminderPlan? = null

    override fun queryReminderPlan(
        query: com.lomo.nativebridge.StoreReminderQuery,
    ): com.lomo.nativebridge.StoreReminderPlan {
        lastReminderQuery = query
        failure?.let { throw it }
        return reminderPlan
            ?: com.lomo.nativebridge.StoreReminderPlan(
                alarms = emptyList(),
                workspaceGeneration = query.workspaceGeneration,
            )
    }

    override fun sidebarProjection(): com.lomo.nativebridge.StoreSidebarProjection = sidebar

    override fun applyMemoCommand(
        command: BridgeMemoCommand,
        onPublication: (BridgeMemoCommit) -> Unit,
    ): BridgeMemoCommit {
        lastCommand = command
        failure?.let { throw it }
        return commit
    }

    override fun sessionCreateMemo(request: SessionCreateMemoRequest): BridgeMemoCommit {
        lastSessionCreate = request
        failure?.let { throw it }
        return commit
    }

    override fun sessionUpdateMemo(request: SessionUpdateMemoRequest): BridgeMemoCommit {
        lastSessionUpdate = request
        failure?.let { throw it }
        return commit
    }

    override fun sessionDeleteMemo(request: SessionDeleteMemoRequest): BridgeMemoCommit {
        lastSessionDelete = request
        failure?.let { throw it }
        return commit
    }

    override fun sessionPinMemo(request: SessionPinMemoRequest): BridgeMemoCommit {
        lastSessionPin = request
        failure?.let { throw it }
        return commit
    }

    override fun sessionRestoreMemo(request: SessionRestoreRequest): SessionRestoreResult {
        lastSessionRestore = request
        failure?.let { throw it }
        return SessionRestoreResult(fileFingerprint = commit.fileFingerprint, eventSequence = commit.eventSequence)
    }

    override fun sessionRestoreRevision(request: SessionRestoreRevisionRequest): BridgeMemoCommit {
        lastSessionRestoreRevision = request
        failure?.let { throw it }
        return commit
    }

    override fun sessionPermanentlyDeleteMemo(request: SessionRestoreRequest): SessionRestoreResult {
        lastSessionPermanentDelete = request
        sessionPermanentDeletes += request
        failure?.let { throw it }
        return SessionRestoreResult(fileFingerprint = commit.fileFingerprint, eventSequence = commit.eventSequence)
    }

    override fun permanentDeleteMany(
        request: com.lomo.nativebridge.StoreMemoBatchDelete,
    ): com.lomo.nativebridge.StoreMemoBatchCommit = error("batch delete not expected")

    override fun commitSafPermanentDeleteMany(
        request: com.lomo.nativebridge.StoreMemoBatchDelete,
    ): com.lomo.nativebridge.StoreMemoBatchCommit = error("SAF batch delete not expected")

    override fun commitSafProjectionMutation(
        command: BridgeMemoCommand,
        projection: com.lomo.nativebridge.StoreSafMemoProjection?,
    ): BridgeMemoCommit = error("SAF projection commit not expected")

    override fun commitWorkspaceDocumentFacts(
        command: BridgeMemoCommand,
        projection: com.lomo.nativebridge.StoreSafMemoProjection,
    ): BridgeMemoCommit = error("document projection commit not expected")

    override fun beginSafMemoCreate(
        begin: com.lomo.nativebridge.StoreSafMemoCreateBegin,
    ): com.lomo.nativebridge.StoreSafMemoCreateBeginResult = error("SAF create begin not expected")

    override fun rollbackSafMemoCreate(
        operationId: String,
        memoId: String,
    ): com.lomo.nativebridge.StoreSafMemoRollbackResult = error("SAF create rollback not expected")

    override fun startRebuild(batchSize: UInt): BridgeRebuildResult {
        lastRebuildBatch = batchSize
        failure?.let { throw it }
        return rebuild
    }
}

private fun storePort(bridge: RecordingStoreNativeBridge) = BoltFfiStorePort(bridge, bridge)

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
        isPending = false,
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
        val port = storePort(bridge)

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
        storePort(bridge).getMemo("missing").shouldBeNull()
        bridge.lastGetMemoId shouldBe "missing"
    }

    test("getMemo maps snapshot body and summary") {
        val bridge =
            RecordingStoreNativeBridge().apply {
                snapshot = BridgeMemoSnapshot(summary = bridgeSummary(preview = "prev"), body = "full body")
            }
        val snap = storePort(bridge).getMemo("m1")
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

        val projection = storePort(bridge).sidebarProjection()

        projection.memoCount shouldBe 2_001
        projection.dateCounts.single().count shouldBe 2_001
        projection.tagCounts.single().name shouldBe "all"
    }

    test("applyMemoCommand routes memo mutations through the application session") {
        val bridge = RecordingStoreNativeBridge()
        val port = storePort(bridge)
        val sessionKinds =
            listOf(
                StoreMemoCommandKind.Create,
                StoreMemoCommandKind.Update,
                StoreMemoCommandKind.Delete,
                StoreMemoCommandKind.Pin,
                StoreMemoCommandKind.Unpin,
                StoreMemoCommandKind.HistoryRestore,
            )
        for (domainKind in sessionKinds) {
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
                        pin = domainKind == StoreMemoCommandKind.Pin,
                        historyRevision = 4L,
                    ),
                    onPublication = {},
                )
            bridge.lastCommand.shouldBeNull()
            when (domainKind) {
                StoreMemoCommandKind.Create -> {
                    bridge.lastSessionCreate?.operationId shouldBe "op-$domainKind"
                    bridge.lastSessionCreate?.content shouldBe "body"
                }
                StoreMemoCommandKind.Update ->
                    bridge.lastSessionUpdate?.expectedDocumentFingerprint shouldBe "ff"
                StoreMemoCommandKind.Delete ->
                    bridge.lastSessionDelete?.memoId shouldBe "m-x"
                StoreMemoCommandKind.Pin ->
                    bridge.lastSessionPin?.pinned shouldBe true
                StoreMemoCommandKind.Unpin ->
                    bridge.lastSessionPin?.pinned shouldBe false
                StoreMemoCommandKind.HistoryRestore ->
                    bridge.lastSessionRestoreRevision?.revision shouldBe 4uL
                else -> error("unexpected kind $domainKind")
            }
            commit.operationId shouldBe "op-$domainKind"
            commit.memoId shouldBe "m-x"
            commit.coreRevision shouldBe 1L
            commit.eventSequence shouldBe 2L
            commit.contentRevision shouldBe 3L
            commit.fileFingerprint shouldBe "ff"
            commit.scopes shouldBe listOf(StoreInvalidationScope.Search)
            commit.idempotentReplay shouldBe true
        }

        val restoreCommit =
            port.applyMemoCommand(
                StoreMemoCommand(
                    operationId = "op-restore",
                    kind = StoreMemoCommandKind.Restore,
                    memoId = "m-x",
                    expectedRevision = 3L,
                    expectedFingerprint = "ff",
                ),
                onPublication = {},
            )
        bridge.lastSessionRestore?.memoId shouldBe "m-x"
        restoreCommit.scopes shouldBe listOf(StoreInvalidationScope.Full)
        restoreCommit.eventSequence shouldBe 2L
    }

    test("unknown native invalidation scope fails closed at the bridge edge") {
        val bridge =
            RecordingStoreNativeBridge().apply {
                commit = commit.copy(scopes = listOf("memo:m1"))
            }

        val error =
            shouldThrow<IllegalStateException> {
                storePort(bridge).applyMemoCommand(
                    StoreMemoCommand(
                        operationId = "op-unknown-scope",
                        kind = StoreMemoCommandKind.Update,
                        memoId = "m1",
                        expectedRevision = 1L,
                        expectedFingerprint = "ff",
                        content = "body",
                    ),
                    onPublication = {},
                )
            }

        error.message shouldContain "Unknown Rust store invalidation scope"
    }

    test("blank operationId without promotes is replaced before bridge apply") {
        val bridge = RecordingStoreNativeBridge()
        storePort(bridge).applyMemoCommand(
            StoreMemoCommand(
                operationId = "  ",
                kind = StoreMemoCommandKind.Create,
                memoId = "",
                expectedRevision = 0L,
                content = "x",
            ),
            onPublication = {},
        )
        bridge.lastSessionCreate?.operationId.shouldNotBeNull().shouldNotBeBlank()
        bridge.lastCommand.shouldBeNull()
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
                storePort(bridge).applyMemoCommand(
                    StoreMemoCommand(
                        operationId = "",
                        kind = StoreMemoCommandKind.Create,
                        memoId = "",
                        expectedRevision = 0L,
                        content = "![i](media/a.png)",
                        pendingPromotes = listOf(plan),
                    ),
                    onPublication = {},
                )
            }
        error.message.shouldNotBeNull().shouldContain("non-blank operationId")
        bridge.lastCommand.shouldBeNull()
        bridge.lastSessionCreate.shouldBeNull()
    }

    test("create with pendingPromotes routes through session create not store apply") {
        val bridge = RecordingStoreNativeBridge()
        val plan =
            MediaPromotePlan(
                operationId = "op-promote",
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
        storePort(bridge).applyMemoCommand(
            StoreMemoCommand(
                operationId = "op-promote",
                kind = StoreMemoCommandKind.Create,
                memoId = "",
                expectedRevision = 0L,
                content = "![i](media/a.png)",
                pendingPromotes = listOf(plan),
                chronologyEpochMs = 1_754_300_000_000L,
            ),
            onPublication = {},
        )
        bridge.lastCommand.shouldBeNull()
        bridge.lastSessionCreate?.operationId shouldBe "op-promote"
        bridge.lastSessionCreate?.content shouldBe "![i](media/a.png)"
        bridge.lastSessionCreate?.chronologyEpochMs shouldBe 1_754_300_000_000L
        bridge.lastSessionCreate?.pendingPromotes?.single()?.finalRelativePath shouldBe "media/a.png"
        bridge.lastSessionCreate?.pendingPromotes?.single()?.operationId shouldBe "op-promote"
    }

    test("permanentDeleteMany routes each target through session not store batch delete") {
        val bridge = RecordingStoreNativeBridge()
        bridge.commit =
            BridgeMemoCommit(
                operationId = "op-batch",
                memoId = "m-b",
                coreRevision = 9uL,
                eventSequence = 11uL,
                contentRevision = 0uL,
                fileFingerprint = "ff",
                scopes = listOf("full"),
                idempotentReplay = false,
            )
        val commit =
            storePort(bridge).permanentDeleteMany(
                operationId = "op-batch",
                targets =
                    listOf(
                        StoreMemoDeleteTarget(
                            memoId = "m-b",
                            sourcePath = "2026_09_10.md",
                            expectedRevision = 2L,
                            expectedFingerprint = "ff-b",
                        ),
                        StoreMemoDeleteTarget(
                            memoId = "m-a",
                            sourcePath = "2026_09_09.md",
                            expectedRevision = 1L,
                            expectedFingerprint = "ff-a",
                        ),
                    ),
            )
        bridge.lastCommand.shouldBeNull()
        bridge.sessionPermanentDeletes.map { request -> request.memoId } shouldBe listOf("m-a", "m-b")
        bridge.sessionPermanentDeletes.map { request -> request.operationId } shouldBe
            listOf("op-batch/m-a", "op-batch/m-b")
        commit.operationId shouldBe "op-batch"
        commit.deleted.map { memo -> memo.memoId } shouldBe listOf("m-a", "m-b")
        commit.eventSequence shouldBe 11L
        commit.scopes shouldBe listOf(StoreInvalidationScope.Full)
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
        val result = storePort(bridge).startRebuild(batchSize = 64)
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
        val page = storePort(bridge).queryMemos(StoreMemoQuery(), null, 10)
        page.items.single().tags shouldBe listOf("work")
        page.items.single().imageUrls shouldBe listOf("images/a.png")
    }

    test("given a Rust reminder plan when queried then session facts and alarms map exactly") {
        val bridge =
            RecordingStoreNativeBridge().apply {
                reminderPlan =
                    com.lomo.nativebridge.StoreReminderPlan(
                        alarms =
                            listOf(
                                com.lomo.nativebridge.StorePlannedAlarm(
                                    opaqueId = "rem-1",
                                    memoIdentity = "memo-1",
                                    triggerAtUtcMs = 1_700_000_123L,
                                    isCatchUp = true,
                                ),
                            ),
                        workspaceGeneration = 42uL,
                    )
            }
        val port = storePort(bridge)

        val result =
            port.queryReminderPlan(
                StoreReminderQuery(
                    nowUtcMs = 1_700_000_000L,
                    zone =
                        StoreTimeZoneContext(
                            zoneId = "UTC",
                            baseOffsetSecs = 0,
                            transitions =
                                listOf(
                                    StoreZoneTransition(
                                        transitionUtcMs = 1_700_000_500L,
                                        offsetBeforeSecs = 0,
                                        offsetAfterSecs = 3_600,
                                    ),
                                ),
                        ),
                    sessions =
                        listOf(
                            StoreReminderSession(
                                opaqueId = "rem-1",
                                memoIdentity = "memo-1",
                                memoRevision = "rev-7",
                                token = "@2023-11-14-22:13",
                                dueAtLocal = "2023-11-14T22:13",
                                repeatCount = 2,
                                firedCount = 1,
                                done = false,
                                intervalMinutes = 15,
                                recurrenceCode = "once",
                            ),
                        ),
                    rollingWindow = 8,
                    workspaceGeneration = 42L,
                ),
            )

        val request = bridge.lastReminderQuery.shouldNotBeNull()
        request.nowUtcMs shouldBe 1_700_000_000L
        request.zone.zoneId shouldBe "UTC"
        request.zone.transitions.single().offsetAfterSecs shouldBe 3_600
        request.sessions.single().memoIdentity shouldBe "memo-1"
        request.sessions.single().firedCount shouldBe 1u
        request.rollingWindow shouldBe 8u
        request.workspaceGeneration shouldBe 42uL
        result.workspaceGeneration shouldBe "42"
        result.alarms.single() shouldBe
            StorePlannedAlarm(
                opaqueId = "rem-1",
                memoIdentity = "memo-1",
                triggerAtUtcMs = 1_700_000_123L,
                isCatchUp = true,
            )
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
                storePort(bridge).applyMemoCommand(
                    StoreMemoCommand(
                        operationId = "op-9",
                        kind = StoreMemoCommandKind.Delete,
                        memoId = "m1",
                        expectedRevision = 3L,
                        expectedFingerprint = "ff",
                    ),
                    onPublication = {},
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
            storePort(RecordingStoreNativeBridge().apply { this.failure = failure })
                .queryMemos(StoreMemoQuery(), null, 10)
        }.failure.code shouldBe "sqlite_error"

        shouldThrow<EngineCommandFailureException> {
            storePort(RecordingStoreNativeBridge().apply { this.failure = failure })
                .getMemo("m1")
        }.failure.jobId shouldBe "job-2"

        shouldThrow<EngineCommandFailureException> {
            storePort(RecordingStoreNativeBridge().apply { this.failure = failure })
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
                storePort(bridge).startRebuild(batchSize = 64)
            }

        error.failure.code shouldBe "trash_marker_missing"
        error.failure.category shouldBe EngineFailureCategory.INTERNAL
        error.message.orEmpty() shouldContain "durable trash marker was absent"
        error.message.orEmpty() shouldContain "teapot"
    }
})
