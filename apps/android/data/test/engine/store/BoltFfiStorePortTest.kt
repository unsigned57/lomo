package com.lomo.data.engine.store

/*
 * Behavior Contract:
 * - Unit under test: BoltFfiStorePort (production StorePort mapping over StoreNativeBridge).
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: map domain StorePort requests/results to/from native session and store bridges;
 *   memo writes go through session FFI; every operationId is frozen by the caller;
 *   promotes require that same non-blank operationId and ride the same
 *   session create/update PlannedFile batch; null getMemo remains
 *   null.
 *
 * Scenarios:
 * - Given a bridge page with one summary and next cursor, when queryMemos runs, then filters/search
 *   are forwarded and domain page fields are mapped (incl. ULong→Long revisions).
 * - Given a start memo id and positional ranks, when queryMemos runs, then identity start is
 *   forwarded and prev/itemsBefore/itemsAfter map.
 * - Given bridge getMemo returns null, when getMemo runs, then null is observed.
 * - Given bridge getMemo returns a snapshot, when getMemo runs, then body and summary map.
 * - Given each StoreMemoCommandKind without staged media, when applyMemoCommand runs, then the
 *   matching session FFI request is recorded and typed invalidation scopes map.
 * - Given a native invalidation scope enum, when a commit crosses the bridge, then it maps
 *   onto the data-layer closed enum without a UTF-8 name table.
 * - Given blank operationId and empty pendingPromotes, when applyMemoCommand runs, then input
 *   is rejected before the bridge; an adapter cannot invent a retry identity.
 * - Given blank operationId with non-empty pendingPromotes, when applyMemoCommand runs, then fail
 *   closed without calling the bridge (no UUID mint under promote).
 * - Given create with matching pendingPromotes, when applyMemoCommand runs, then session create
 *   receives those plans and store applyMemoCommand is not called.
 * - Given restore or permanent-delete, when applyMemoCommand / permanentDeleteMany run, then
 *   coreRevision, eventSequence, contentRevision, and scopes come from the session commit DTO.
 * - Given an unsorted trash target list, when permanentDeleteMany runs, then one session batch call
 *   carries canonically sorted CAS facts and the deleted reminder facts map back with no per-memo
 *   getMemo hydration.
 * - Given a rebuild result, when startRebuild runs, then counters map to domain longs
 *   including whether SQLite was rewritten.
 * - Given a Rust reminder plan, when queryReminderPlan runs, then sessionReminderPlan receives
 *   nowUtcMs and planned alarms map back without Kotlin reconstructing reminder sessions.
 * - Given the engine refuses a memo command, query, get or rebuild, when the call crosses the
 *   boundary, then an EngineCommandFailureException carries the typed category/code/retry/ids and a
 *   non-blank message instead of the message-less native carrier.
 * - Given an engine vocabulary this build does not know, when a rejection crosses the boundary, then
 *   the original code and diagnostic are preserved rather than replaced by a parse failure.
 *
 * Observable outcomes: domain StoreMemoPage / Snapshot / Commit / RebuildResult; last bridge
 * request fields.
 *
 * Identity Test Change Justification:
 * - Reason category: command identity contract correction.
 * - Old behavior/assertion being replaced: the adapter minted an operation id for blank input.
 * - Why old assertion is no longer correct: a retry must carry its original logical operation id.
 * - Coverage preserved by: typed session command mapping and matching-promote scenarios.
 * - Why this is not fitting the test to the implementation: blank input is rejected with and without media.
 *
 * TDD proof:
 * - RED for the identity correction: the blank-id scenario returned a successful commit.
 * - Target: ./kotlin test --include-module=data --include-classes='com.lomo.data.engine.store.BoltFfiStorePortTest'
 * - RED: BoltFfiStorePort untested / zero-hit under coverage before this host contract.
 *
 * Excludes:
 * - Real BoltFFI/JNI handle lifecycle (packaged native library / native contracts).
 *
 * Test Change Justification:
 * - Reason category: T12 session-owned reminder plan; store queryReminderPlan is not the write/plan path.
 * - Old behavior/assertion being replaced: StoreReminderQuery zone/sessions/generation forwarded to store FFI.
 * - Why old assertion is no longer correct: session_reminder_plan owns the plan from markdown + state_dir.
 * - Coverage preserved by: nowUtcMs forwarded to sessionReminderPlan and alarm mapping unchanged.
 * - Why this is not fitting the test to the implementation: locks the session plan boundary, not DTO field echo.
 *
 * Test Change Justification:
 * - Reason category: production media promote wiring on session memo commands.
 * - Old behavior/assertion being replaced: staged promotes shared the store Direct writer.
 * - Why old assertion is no longer correct: WorkspaceSession owns document writes and attachment
 *   PlannedFiles in one batch; store applyMemoCommand is not a write path for create/update.
 * - Coverage preserved by: page/get/rebuild mapping and valid operation-id scenarios remain.
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
import com.lomo.nativebridge.SessionRestoreRevisionRequest
import com.lomo.nativebridge.SessionUpdateMemoRequest
import com.lomo.data.engine.SessionNativeBridge
import com.lomo.data.engine.media.MediaPromotePlan
import com.lomo.data.engine.media.MediaStagedFacts
import com.lomo.data.engine.store.StorePlannedAlarm
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
    var lastStartMemoId: String? = null
    var lastBackward: Boolean = false
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
    var lastPermanentDeleteMany: com.lomo.nativebridge.StoreMemoBatchDelete? = null
    var batchCommit: com.lomo.nativebridge.StoreMemoBatchCommit =
        com.lomo.nativebridge.StoreMemoBatchCommit(
            operationId = "op",
            deleted = emptyList(),
            coreRevision = 1uL,
            eventSequence = 2uL,
            scopes = listOf(com.lomo.nativebridge.StoreInvalidationScope.MEMO_LIST),
            idempotentReplay = false,
        )
    var lastRebuildBatch: UInt? = null

    var page: BridgeMemoPage =
        BridgeMemoPage(
            items = emptyList(),
            nextCursor = null,
            prevCursor = null,
            itemsBefore = 0uL,
            itemsAfter = 0uL,
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
            scopes = listOf(com.lomo.nativebridge.StoreInvalidationScope.MEMO_LIST),
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
            rewritten = true,
        )

    override fun queryMemos(
        query: BridgeMemoQuery,
        cursor: BridgePageCursor?,
        pageSize: UInt,
        startMemoId: String?,
        backward: Boolean,
    ): BridgeMemoPage {
        lastQuery = query
        lastCursor = cursor
        lastPageSize = pageSize
        lastStartMemoId = startMemoId
        lastBackward = backward
        failure?.let { throw it }
        return page
    }

    override fun queryCount(query: BridgeMemoQuery): ULong {
        failure?.let { throw it }
        return 0uL
    }



    override fun getMemo(memoId: String): BridgeMemoSnapshot? {
        lastGetMemoId = memoId
        failure?.let { throw it }
        return snapshot
    }

    var reminderPlan: com.lomo.nativebridge.StoreReminderPlan? = null

    override fun sidebarProjection(): com.lomo.nativebridge.StoreSidebarProjection = sidebar


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

    override fun sessionRestoreMemo(request: SessionRestoreRequest): BridgeMemoCommit {
        lastSessionRestore = request
        failure?.let { throw it }
        return commit
    }

    override fun sessionRestoreRevision(request: SessionRestoreRevisionRequest): BridgeMemoCommit {
        lastSessionRestoreRevision = request
        failure?.let { throw it }
        return commit
    }

    override fun sessionPermanentlyDeleteMemo(request: SessionRestoreRequest): BridgeMemoCommit {
        lastSessionPermanentDelete = request
        sessionPermanentDeletes += request
        failure?.let { throw it }
        return commit
    }

    override fun sessionPermanentlyDeleteMany(
        request: com.lomo.nativebridge.StoreMemoBatchDelete,
    ): com.lomo.nativebridge.StoreMemoBatchCommit {
        lastPermanentDeleteMany = request
        failure?.let { throw it }
        return batchCommit
    }

    var lastSessionReminderNowUtcMs: Long? = null

    override fun sessionReminderPlan(nowUtcMs: Long?): com.lomo.nativebridge.StoreReminderPlan {
        lastSessionReminderNowUtcMs = nowUtcMs
        failure?.let { throw it }
        return reminderPlan
            ?: com.lomo.nativebridge.StoreReminderPlan(
                alarms = emptyList(),
                droppedCount = 0u,
                workspaceGeneration = "gen-empty",
            )
    }

    override fun commitWorkspaceDocumentFacts(
        command: BridgeMemoCommand,
        projection: com.lomo.nativebridge.StoreSafMemoProjection,
    ): BridgeMemoCommit = error("document projection commit not expected")

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
        charCount = preview.length.toLong(),
    )

class BoltFfiStorePortTest : FunSpec({
    test("queryMemos forwards filters and maps page to domain types") {
        val bridge =
            RecordingStoreNativeBridge().apply {
                page =
                    BridgeMemoPage(
                        items = listOf(bridgeSummary()),
                        nextCursor = BridgePageCursor("c2"),
                        prevCursor = null,
                        itemsBefore = 0uL,
                        itemsAfter = 4uL,
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
        bridge.lastStartMemoId.shouldBeNull()
        bridge.lastBackward shouldBe false
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
        result.prevCursor.shouldBeNull()
        result.itemsBefore shouldBe 0L
        result.itemsAfter shouldBe 4L
        result.highWaterRevision shouldBe 11L
        result.queryFingerprint shouldBe "q-fp"
    }

    test("queryMemos starts at memo identity and maps positional ranks") {
        val bridge =
            RecordingStoreNativeBridge().apply {
                page =
                    BridgeMemoPage(
                        items = listOf(bridgeSummary()),
                        nextCursor = BridgePageCursor("next"),
                        prevCursor = BridgePageCursor("prev"),
                        itemsBefore = 2uL,
                        itemsAfter = 4uL,
                        highWaterRevision = 11uL,
                        queryFingerprint = "q-fp",
                    )
            }
        val result =
            storePort(bridge).queryMemos(
                StoreMemoQuery(),
                cursor = null,
                pageSize = 30,
                startMemoId = "m-mid",
                backward = false,
            )
        bridge.lastStartMemoId shouldBe "m-mid"
        bridge.lastCursor.shouldBeNull()
        bridge.lastBackward shouldBe false
        result.prevCursor?.encoded shouldBe "prev"
        result.nextCursor?.encoded shouldBe "next"
        result.itemsBefore shouldBe 2L
        result.itemsAfter shouldBe 4L
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
                    scopes = listOf(com.lomo.nativebridge.StoreInvalidationScope.SEARCH),
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

        bridge.commit =
            BridgeMemoCommit(
                operationId = "op-restore",
                memoId = "m-x",
                coreRevision = 8uL,
                eventSequence = 9uL,
                contentRevision = 4uL,
                fileFingerprint = "ff-restore",
                scopes = listOf(
                    com.lomo.nativebridge.StoreInvalidationScope.MEMO_LIST,
                    com.lomo.nativebridge.StoreInvalidationScope.TRASH,
                ),
                idempotentReplay = false,
            )
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
        restoreCommit.coreRevision shouldBe 8L
        restoreCommit.eventSequence shouldBe 9L
        restoreCommit.contentRevision shouldBe 4L
        restoreCommit.fileFingerprint shouldBe "ff-restore"
        restoreCommit.scopes shouldBe
            listOf(StoreInvalidationScope.MemoList, StoreInvalidationScope.Trash)
    }

    test("native invalidation scopes map onto the closed data-layer enum") {
        val bridge =
            RecordingStoreNativeBridge().apply {
                commit =
                    commit.copy(
                        scopes =
                            listOf(
                                com.lomo.nativebridge.StoreInvalidationScope.MEMO_LIST,
                                com.lomo.nativebridge.StoreInvalidationScope.FULL,
                            ),
                    )
            }

        val commit =
            storePort(bridge).applyMemoCommand(
                StoreMemoCommand(
                    operationId = "op-scope-enum",
                    kind = StoreMemoCommandKind.Update,
                    memoId = "m1",
                    expectedRevision = 1L,
                    expectedFingerprint = "ff",
                    content = "body",
                ),
                onPublication = {},
            )

        commit.scopes shouldBe listOf(StoreInvalidationScope.MemoList, StoreInvalidationScope.Full)
    }

    test("blank operationId without promotes is rejected before the bridge") {
        val bridge = RecordingStoreNativeBridge()
        shouldThrow<IllegalArgumentException> {
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
        }
        bridge.lastSessionCreate.shouldBeNull()
        bridge.lastCommand.shouldBeNull()
    }

    test("session memo commands do not synthesize a mid-flight publication from the returned commit") {
        val bridge = RecordingStoreNativeBridge()
        var midFlight = 0
        storePort(bridge).applyMemoCommand(
            StoreMemoCommand(
                operationId = "op-create",
                kind = StoreMemoCommandKind.Create,
                memoId = "",
                expectedRevision = 0L,
                content = "body",
            ),
            onPublication = { midFlight += 1 },
        )
        midFlight shouldBe 0
        bridge.lastSessionCreate.shouldNotBeNull()
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
            shouldThrow<IllegalArgumentException> {
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

    test("permanentDeleteMany issues one session batch call without per-memo hydration") {
        val bridge = RecordingStoreNativeBridge()
        bridge.batchCommit =
            com.lomo.nativebridge.StoreMemoBatchCommit(
                operationId = "op-batch",
                deleted =
                    listOf(
                        com.lomo.nativebridge.StoreMemoDeletedMemo("m-a", listOf("r-a")),
                        com.lomo.nativebridge.StoreMemoDeletedMemo("m-b", listOf("r-b1", "r-b2")),
                    ),
                coreRevision = 9uL,
                eventSequence = 11uL,
                scopes = listOf(com.lomo.nativebridge.StoreInvalidationScope.FULL),
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
        bridge.lastGetMemoId.shouldBeNull()
        bridge.sessionPermanentDeletes shouldBe emptyList()
        val request = bridge.lastPermanentDeleteMany
        request.shouldNotBeNull()
        request.operationId shouldBe "op-batch"
        request.targets.map { it.memoId } shouldBe listOf("m-a", "m-b")
        request.targets.map { it.expectedRevision } shouldBe listOf(1uL, 2uL)
        request.targets.map { it.expectedFingerprint } shouldBe listOf("ff-a", "ff-b")
        commit.operationId shouldBe "op-batch"
        commit.deleted.map { memo -> memo.memoId } shouldBe listOf("m-a", "m-b")
        commit.deleted.map { memo -> memo.reminderIds } shouldBe
            listOf(listOf("r-a"), listOf("r-b1", "r-b2"))
        commit.coreRevision shouldBe 9L
        commit.eventSequence shouldBe 11L
        commit.scopes shouldBe listOf(StoreInvalidationScope.Full)
        commit.idempotentReplay shouldBe false
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
                        rewritten = true,
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
        result.rewritten shouldBe true
    }

    test("queryMemos maps tags and image urls from bridge summary") {
        val bridge =
            RecordingStoreNativeBridge().apply {
                page =
                    BridgeMemoPage(
                        items = listOf(bridgeSummary()),
                        nextCursor = null,
                        prevCursor = null,
                        itemsBefore = 0uL,
                        itemsAfter = 0uL,
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
                                    occurrenceId = "gen-42␟rem-1␟1700000123",
                                    opaqueId = "rem-1",
                                    memoIdentity = "memo-1",
                                    triggerAtUtcMs = 1_700_000_123L,
                                    isCatchUp = true,
                                ),
                            ),
                        droppedCount = 0u,
                        workspaceGeneration = "gen-42",
                    )
            }
        val port = storePort(bridge)

        val result = port.queryReminderPlan(1_700_000_000L)

        bridge.lastSessionReminderNowUtcMs shouldBe 1_700_000_000L
        result.droppedCount shouldBe 0
        result.workspaceGeneration shouldBe "gen-42"
        result.alarms.single() shouldBe
            StorePlannedAlarm(
                occurrenceId = "gen-42␟rem-1␟1700000123",
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
