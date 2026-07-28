package com.lomo.app.feature.synccenter

/*
 * Behavior Contract:
 * - Unit under test: Sync Center pure reducer + detail fact helpers (P5-10)
 * - Owning layer: app (presentation state machine; no DI)
 * - Priority tier: P1
 * - Capability: Sync Center shell state transitions for overview/conflicts/list-detail/
 *   binary no-preview / markdown draft / paginated apply with expected revision.
 *
 * Scenarios:
 * - Given Open, when reduced, then Loading + LoadInitial effect.
 * - Given Ready page with nextCursor, when LoadMoreConflicts, then LoadMore effect with cursor.
 * - Given phone layout SelectConflict, when reduced, then ConflictDetail pane + selection +
 *   LoadConflictDetail effect and isLoadingDetail true.
 * - Given list-detail layout SelectConflict, when reduced, then Conflicts pane keeps dual surface
 *   and LoadConflictDetail effect is emitted.
 * - Given binary path, when binaryFactsFor, then digests/source set and no text body fields exist
 *   on the facts type (MIME/size null on list wire is honest).
 * - Given markdown path, when markdownFactsFor without bodies, then digests map and bodies null
 *   (adapter owns artifact body load).
 * - Given keep_local choice + ApplyResolutions, when reduced, then Resolve effect with revision.
 * - Given ApplyResolutions with empty choices, when reduced, then lastError no_resolutions_selected.
 * - Given SetListDetail expanded, when on ConflictDetail, then pane becomes Conflicts.
 *
 * Observable outcomes: SyncCenterUiState fields + SyncCenterEffect payloads.
 * Excludes: Compose rendering, production nav/DI, real JNI repository.
 */

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.RemoteSyncBackendLabel
import com.lomo.domain.model.RemoteSyncConfigSummary
import com.lomo.domain.model.RemoteSyncConflictPage
import com.lomo.domain.model.RemoteSyncConflictPath
import com.lomo.domain.model.RemoteSyncConflictPathStatus
import com.lomo.domain.model.RemoteSyncConflictResolution
import com.lomo.domain.model.RemoteSyncSessionPhase
import com.lomo.domain.model.RemoteSyncSessionProgress
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class SyncCenterStateReducerTest : AppFunSpec() {
    init {
        test("open transitions to loading and emits LoadInitial") {
            val result =
                reduceSyncCenter(
                    initialSyncCenterState(),
                    SyncCenterIntent.Open(workspaceRoot = "/ws", isListDetail = false),
                )
            result.state.workspaceRoot shouldBe "/ws"
            result.state.load shouldBe SyncCenterLoadState.Loading
            result.effects shouldContainExactly listOf(SyncCenterEffect.LoadInitial("/ws"))
        }

        test("load more emits cursor when page has nextCursor") {
            val ready = readyState(page = samplePage(nextCursor = 100))
            val result = reduceSyncCenter(ready, SyncCenterIntent.LoadMoreConflicts)
            result.effects shouldContainExactly
                listOf(
                    SyncCenterEffect.LoadMore(
                        workspaceRoot = "/ws",
                        cursor = 100,
                        limit = SYNC_CENTER_CONFLICT_PAGE_LIMIT,
                    ),
                )
        }

        test("load more is no-op without nextCursor") {
            val ready = readyState(page = samplePage(nextCursor = null))
            val result = reduceSyncCenter(ready, SyncCenterIntent.LoadMoreConflicts)
            result.effects shouldBe emptyList()
        }

        test("select conflict on phone opens detail pane and loads detail effect") {
            val ready = readyState(isListDetail = false)
            val result =
                reduceSyncCenter(
                    ready,
                    SyncCenterIntent.SelectConflict("memo/a.md"),
                )
            result.state.pane shouldBe SyncCenterPane.ConflictDetail
            val load = result.state.load.shouldBeInstanceOf<SyncCenterLoadState.Ready>()
            load.selectedPath shouldBe "memo/a.md"
            load.isLoadingDetail shouldBe true
            val effect = result.effects.single().shouldBeInstanceOf<SyncCenterEffect.LoadConflictDetail>()
            effect.path.path shouldBe "memo/a.md"
            effect.workspaceRoot shouldBe "/ws"
        }

        test("select conflict on expanded keeps conflicts pane and loads detail effect") {
            val ready = readyState(isListDetail = true)
            val result =
                reduceSyncCenter(
                    ready,
                    SyncCenterIntent.SelectConflict("memo/a.md"),
                )
            result.state.pane shouldBe SyncCenterPane.Conflicts
            val load = result.state.load.shouldBeInstanceOf<SyncCenterLoadState.Ready>()
            load.selectedPath shouldBe "memo/a.md"
            load.isLoadingDetail shouldBe true
            result.effects.single().shouldBeInstanceOf<SyncCenterEffect.LoadConflictDetail>()
        }

        test("markdownFactsFromState prefers repository-loaded bodies over digest-only helper") {
            val md =
                RemoteSyncConflictPath(
                    path = "memo/a.md",
                    kind = "markdown",
                    localDigest = "l",
                    remoteDigest = "r",
                    baselineDigest = "b",
                    remoteTokenPresent = false,
                    localArtifactRef = "la",
                    remoteArtifactRef = "ra",
                    status = RemoteSyncConflictPathStatus.Open,
                )
            val loaded =
                com.lomo.domain.model.RemoteSyncMarkdownConflictFacts(
                    path = "memo/a.md",
                    baseDigest = "b",
                    localDigest = "l",
                    remoteDigest = "r",
                    baseBody = "BASE",
                    localBody = "LOCAL",
                    remoteBody = "REMOTE",
                    mergedDraft = null,
                )
            val readyLoad =
                (readyState().load as SyncCenterLoadState.Ready).copy(
                    markdownDetailByPath =
                        kotlinx.collections.immutable.persistentMapOf("memo/a.md" to loaded),
                    mergedDrafts =
                        kotlinx.collections.immutable.persistentMapOf("memo/a.md" to "draft-x"),
                )
            val facts = markdownFactsFromState(readyLoad, md)
            facts.baseBody shouldBe "BASE"
            facts.localBody shouldBe "LOCAL"
            facts.remoteBody shouldBe "REMOTE"
            facts.mergedDraft shouldBe "draft-x"
        }

        test("binaryFactsFromState prefers repository-loaded digests and has no text body fields") {
            val binary =
                RemoteSyncConflictPath(
                    path = "media/x.bin",
                    kind = "binary",
                    localDigest = "ld",
                    remoteDigest = "rd",
                    baselineDigest = "bd",
                    remoteTokenPresent = true,
                    localArtifactRef = "la",
                    remoteArtifactRef = "ra",
                    status = RemoteSyncConflictPathStatus.Open,
                )
            val loaded =
                com.lomo.domain.model.RemoteSyncBinaryConflictFacts(
                    path = "media/x.bin",
                    mimeType = "image/png",
                    sizeBytes = 42L,
                    localDigest = "ld",
                    remoteDigest = "rd",
                    baselineDigest = "bd",
                    sourceLabel = "remote_sync",
                )
            val readyLoad =
                (readyState().load as SyncCenterLoadState.Ready).copy(
                    binaryDetailByPath =
                        kotlinx.collections.immutable.persistentMapOf("media/x.bin" to loaded),
                )
            val facts = binaryFactsFromState(readyLoad, binary)
            facts.mimeType shouldBe "image/png"
            facts.sizeBytes shouldBe 42L
            facts.localDigest shouldBe "ld"
            facts.sourceLabel shouldBe "remote_sync"
        }

        test("binary facts expose digests and never invent preview body") {
            val binary =
                RemoteSyncConflictPath(
                    path = "media/x.bin",
                    kind = "binary",
                    localDigest = "ld",
                    remoteDigest = "rd",
                    baselineDigest = "bd",
                    remoteTokenPresent = true,
                    localArtifactRef = "la",
                    remoteArtifactRef = "ra",
                    status = RemoteSyncConflictPathStatus.Open,
                )
            val facts = binaryFactsFor(binary)
            facts.path shouldBe "media/x.bin"
            facts.localDigest shouldBe "ld"
            facts.remoteDigest shouldBe "rd"
            facts.mimeType.shouldBeNull()
            facts.sizeBytes.shouldBeNull()
            facts.sourceLabel shouldBe "remote_sync"
        }

        test("markdown facts default bodies null without loaded artifact bodies") {
            val md =
                RemoteSyncConflictPath(
                    path = "memo/a.md",
                    kind = "markdown",
                    localDigest = "l",
                    remoteDigest = "r",
                    baselineDigest = "b",
                    remoteTokenPresent = false,
                    localArtifactRef = null,
                    remoteArtifactRef = null,
                    status = RemoteSyncConflictPathStatus.Open,
                )
            val facts = markdownFactsFor(md, mergedDraft = "draft")
            facts.localBody.shouldBeNull()
            facts.remoteBody.shouldBeNull()
            facts.baseBody.shouldBeNull()
            facts.mergedDraft shouldBe "draft"
        }

        test("apply resolutions emits Resolve with expected revision") {
            val ready =
                readyState().let { state ->
                    val load = state.load as SyncCenterLoadState.Ready
                    state.copy(
                        load =
                            load.copy(
                                perPathResolutionKind =
                                    kotlinx.collections.immutable.persistentMapOf(
                                        "memo/a.md" to RemoteSyncConflictResolution.KIND_KEEP_LOCAL,
                                    ),
                            ),
                    )
                }
            val result = reduceSyncCenter(ready, SyncCenterIntent.ApplyResolutions)
            val load = result.state.load.shouldBeInstanceOf<SyncCenterLoadState.Ready>()
            load.isResolving shouldBe true
            val effect = result.effects.single().shouldBeInstanceOf<SyncCenterEffect.Resolve>()
            effect.expectedRevision shouldBe 7L
            effect.resolutions.single().kind shouldBe RemoteSyncConflictResolution.KIND_KEEP_LOCAL
        }

        test("apply without choices records structured lastError") {
            val ready = readyState()
            val result = reduceSyncCenter(ready, SyncCenterIntent.ApplyResolutions)
            val load = result.state.load.shouldBeInstanceOf<SyncCenterLoadState.Ready>()
            load.lastError shouldBe "no_resolutions_selected"
            result.effects shouldBe emptyList()
        }

        test("set list detail from phone detail collapses into conflicts pane") {
            val detail =
                readyState(isListDetail = false).copy(pane = SyncCenterPane.ConflictDetail)
            val result =
                reduceSyncCenter(
                    detail,
                    SyncCenterIntent.SetListDetail(isListDetail = true),
                )
            result.state.layout.isListDetail shouldBe true
            result.state.pane shouldBe SyncCenterPane.Conflicts
        }

        test("merged body without draft is omitted from apply batch") {
            val ready =
                readyState().let { state ->
                    val load = state.load as SyncCenterLoadState.Ready
                    state.copy(
                        load =
                            load.copy(
                                perPathResolutionKind =
                                    kotlinx.collections.immutable.persistentMapOf(
                                        "memo/a.md" to RemoteSyncConflictResolution.KIND_MERGED_BODY,
                                    ),
                            ),
                    )
                }
            val result = reduceSyncCenter(ready, SyncCenterIntent.ApplyResolutions)
            val load = result.state.load.shouldBeInstanceOf<SyncCenterLoadState.Ready>()
            load.lastError shouldBe "no_resolutions_selected"
        }

        test("page append merges distinct paths") {
            val base = readyState(page = samplePage(nextCursor = 1))
            val next =
                samplePage(nextCursor = null).copy(
                    items =
                        listOf(
                            RemoteSyncConflictPath(
                                path = "memo/b.md",
                                kind = "markdown",
                                localDigest = null,
                                remoteDigest = null,
                                baselineDigest = null,
                                remoteTokenPresent = false,
                                localArtifactRef = null,
                                remoteArtifactRef = null,
                                status = RemoteSyncConflictPathStatus.Open,
                            ),
                        ),
                )
            val applied = applySyncCenterPageAppend(base, next)
            val load = applied.load.shouldBeInstanceOf<SyncCenterLoadState.Ready>()
            load.items.map { it.path } shouldContainExactly listOf("memo/a.md", "media/x.bin", "memo/b.md")
        }

        test("resolve success drops applied paths and advances attention count") {
            val ready =
                readyState().let { state ->
                    val load = state.load as SyncCenterLoadState.Ready
                    state.copy(
                        load =
                            load.copy(
                                selectedPath = "memo/a.md",
                                perPathResolutionKind =
                                    kotlinx.collections.immutable.persistentMapOf(
                                        "memo/a.md" to RemoteSyncConflictResolution.KIND_KEEP_LOCAL,
                                    ),
                            ),
                    )
                }
            val applied =
                applySyncCenterResolveSuccess(
                    state = ready,
                    sessionId = "s2",
                    conflictRevision = 8L,
                    appliedPaths = listOf("memo/a.md"),
                )
            val load = applied.load.shouldBeInstanceOf<SyncCenterLoadState.Ready>()
            load.items.map { it.path } shouldContainExactly listOf("media/x.bin")
            load.conflictPage.conflictRevision shouldBe 8L
            load.selectedPath.shouldBeNull()
            load.config.attentionCount shouldBe 1
            load.isResolving shouldBe false
        }
    }

    private fun samplePage(nextCursor: Int?): RemoteSyncConflictPage =
        RemoteSyncConflictPage(
            sessionId = "session-1",
            conflictRevision = 7L,
            items =
                listOf(
                    RemoteSyncConflictPath(
                        path = "memo/a.md",
                        kind = "markdown",
                        localDigest = "l1",
                        remoteDigest = "r1",
                        baselineDigest = "b1",
                        remoteTokenPresent = true,
                        localArtifactRef = null,
                        remoteArtifactRef = null,
                        status = RemoteSyncConflictPathStatus.Open,
                    ),
                    RemoteSyncConflictPath(
                        path = "media/x.bin",
                        kind = "binary",
                        localDigest = "lb",
                        remoteDigest = "rb",
                        baselineDigest = "bb",
                        remoteTokenPresent = false,
                        localArtifactRef = "a1",
                        remoteArtifactRef = "a2",
                        status = RemoteSyncConflictPathStatus.Open,
                    ),
                ),
            nextCursor = nextCursor,
        )

    private fun readyState(
        isListDetail: Boolean = false,
        page: RemoteSyncConflictPage = samplePage(nextCursor = null),
    ): SyncCenterUiState =
        applySyncCenterLoadSuccess(
            state =
                initialSyncCenterState(workspaceRoot = "/ws", isListDetail = isListDetail).copy(
                    pane = SyncCenterPane.Conflicts,
                    layout = SyncCenterLayoutMode(isListDetail = isListDetail),
                ),
            config =
                RemoteSyncConfigSummary(
                    backend = RemoteSyncBackendLabel.S3,
                    attentionCount = page.items.size,
                    lastVerifiedAtEpochMillis = 1_700_000_000_000L,
                    schedulePolicyLabel = "wifi_unmetered",
                ),
            session =
                RemoteSyncSessionProgress(
                    phase = RemoteSyncSessionPhase.ConflictOpen,
                    completedActions = 3,
                    totalActions = 10,
                    canCancel = true,
                ),
            page = page,
        )
}
