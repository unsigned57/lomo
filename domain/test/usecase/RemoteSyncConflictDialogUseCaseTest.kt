package com.lomo.domain.usecase

/*
 * Behavior Contract:
 * - Unit under test: RemoteSyncConflictDialogUseCase
 * - Owning layer: domain
 * - Priority tier: P1
 * - Capability: map Rust RemoteSyncCenterRepository conflict pages into original dialog
 *   SyncConflictSet and resolve via expected-revision wire (keep_local/keep_remote/
 *   merged_body/skip_for_now). Sole remote-conflict authority for host dialog UX.
 *
 * Scenarios:
 * - Given open markdown paths with artifact bodies, when loadOpenSession, then dialog files
 *   carry local/remote bodies and backend from config summary.
 * - Given no open paths, when loadOpenSession, then null.
 * - Given KEEP_LOCAL + KEEP_REMOTE choices, when resolveSuspending, then repository receives
 *   expected revision and named kinds; empty remaining → Resolved + memo refresh.
 * - Given remaining open after resolve, when resolveSuspending, then Pending with remaining set
 *   and no memo refresh.
 * - Given MERGE_TEXT on mergeable markdown, when resolve, then merged_body kind carries body.
 * - Given blank workspace root, when loadOpenSession, then null.
 *
 * Observable outcomes: OpenSession fields, resolve result, fake repository request log,
 * memo refresh count.
 * Excludes: Compose dialog, BoltFFI/JNI, Sync Inbox review path, deleted Kotlin engines.
 */

import com.lomo.domain.model.RemoteSyncBackendLabel
import com.lomo.domain.model.RemoteSyncBinaryConflictFacts
import com.lomo.domain.model.RemoteSyncConfigSummary
import com.lomo.domain.model.RemoteSyncConflictPage
import com.lomo.domain.model.RemoteSyncConflictPath
import com.lomo.domain.model.RemoteSyncConflictPathStatus
import com.lomo.domain.model.RemoteSyncConflictResolution
import com.lomo.domain.model.RemoteSyncConflictResolveResult
import com.lomo.domain.model.RemoteSyncMarkdownConflictFacts
import com.lomo.domain.model.RemoteSyncSessionPhase
import com.lomo.domain.model.RemoteSyncSessionProgress
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.repository.MemoMutationRepository
import com.lomo.domain.repository.RemoteSyncCenterRepository
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking

class RemoteSyncConflictDialogUseCaseTest : DomainFunSpec() {
    init {
        test("loadOpenSession maps open markdown bodies and backend label") {
            val repo =
                FakeRemoteSyncCenterRepository(
                    backend = RemoteSyncBackendLabel.S3,
                    pages =
                        listOf(
                            page(
                                revision = 7,
                                items =
                                    listOf(
                                        openMarkdown("memos/a.md"),
                                        openBinary("images/p.jpg"),
                                        resolvedMarkdown("memos/done.md"),
                                    ),
                            ),
                        ),
                    markdownBodies =
                        mapOf(
                            "memos/a.md" to ("local-a" to "remote-a"),
                        ),
                )
            val useCase = RemoteSyncConflictDialogUseCase(repo, RecordingMemoMutationRepository())

            val session = useCase.loadOpenSession("/ws")

            session.shouldNotBeNull()
            session.workspaceRoot shouldBe "/ws"
            session.conflictRevision shouldBe 7
            session.conflictSet.source shouldBe SyncBackendType.S3
            session.conflictSet.files shouldBe
                listOf(
                    SyncConflictFile(
                        relativePath = "memos/a.md",
                        localContent = "local-a",
                        remoteContent = "remote-a",
                        isBinary = false,
                    ),
                    SyncConflictFile(
                        relativePath = "images/p.jpg",
                        localContent = null,
                        remoteContent = null,
                        isBinary = true,
                    ),
                )
        }

        test("loadOpenSession returns null when no open paths") {
            val repo =
                FakeRemoteSyncCenterRepository(
                    pages = listOf(page(revision = 1, items = listOf(resolvedMarkdown("x.md")))),
                )
            val useCase = RemoteSyncConflictDialogUseCase(repo, RecordingMemoMutationRepository())

            useCase.loadOpenSession("/ws").shouldBeNull()
        }

        test("loadOpenSession returns null for blank workspace") {
            val repo = FakeRemoteSyncCenterRepository()
            val useCase = RemoteSyncConflictDialogUseCase(repo, RecordingMemoMutationRepository())

            useCase.loadOpenSession("   ").shouldBeNull()
        }

        test("resolveSuspending applies expected revision and refreshes when fully resolved") {
            val repo =
                FakeRemoteSyncCenterRepository(
                    backend = RemoteSyncBackendLabel.Git,
                    pages =
                        listOf(
                            page(
                                revision = 3,
                                items = listOf(openMarkdown("memos/a.md"), openMarkdown("memos/b.md")),
                            ),
                        ),
                    markdownBodies =
                        mapOf(
                            "memos/a.md" to ("L" to "R"),
                            "memos/b.md" to ("L2" to "R2"),
                        ),
                    postResolvePages = listOf(page(revision = 4, items = emptyList())),
                )
            val memos = RecordingMemoMutationRepository()
            val useCase = RemoteSyncConflictDialogUseCase(repo, memos)
            val session = useCase.loadOpenSession("/ws").shouldNotBeNull()

            val result =
                runBlocking {
                    useCase.resolveSuspending(
                        session = session,
                        resolution =
                            SyncConflictResolution(
                                mapOf(
                                    "memos/a.md" to SyncConflictResolutionChoice.KEEP_LOCAL,
                                    "memos/b.md" to SyncConflictResolutionChoice.KEEP_REMOTE,
                                ),
                            ),
                    )
                }

            result shouldBe RemoteSyncConflictDialogUseCase.DialogResolveResult.Resolved
            memos.refreshCount shouldBe 1
            repo.lastResolveExpectedRevision shouldBe 3
            repo.lastResolutions shouldBe
                listOf(
                    RemoteSyncConflictResolution(
                        path = "memos/a.md",
                        kind = RemoteSyncConflictResolution.KIND_KEEP_LOCAL,
                    ),
                    RemoteSyncConflictResolution(
                        path = "memos/b.md",
                        kind = RemoteSyncConflictResolution.KIND_KEEP_REMOTE,
                    ),
                )
        }

        test("resolveSuspending returns Pending remaining open set without refresh") {
            val remainingPath = openMarkdown("memos/b.md")
            val repo =
                FakeRemoteSyncCenterRepository(
                    backend = RemoteSyncBackendLabel.S3,
                    pages =
                        listOf(
                            page(
                                revision = 2,
                                items = listOf(openMarkdown("memos/a.md"), remainingPath),
                            ),
                        ),
                    markdownBodies =
                        mapOf(
                            "memos/a.md" to ("alpha" to "alpha\ngamma"),
                            "memos/b.md" to ("start\nlocal only\nend" to "start\nremote only\nend"),
                        ),
                    postResolvePages =
                        listOf(
                            page(revision = 3, items = listOf(remainingPath)),
                        ),
                )
            val memos = RecordingMemoMutationRepository()
            val useCase = RemoteSyncConflictDialogUseCase(repo, memos)
            val session = useCase.loadOpenSession("/ws").shouldNotBeNull()

            val result =
                runBlocking {
                    useCase.resolveSuspending(
                        session = session,
                        resolution =
                            SyncConflictResolution(
                                mapOf(
                                    "memos/a.md" to SyncConflictResolutionChoice.KEEP_REMOTE,
                                    "memos/b.md" to SyncConflictResolutionChoice.SKIP_FOR_NOW,
                                ),
                            ),
                    )
                }

            val pending = result.shouldBeInstanceOf<RemoteSyncConflictDialogUseCase.DialogResolveResult.Pending>()
            pending.session.conflictSet.files.map { it.relativePath } shouldBe listOf("memos/b.md")
            pending.session.conflictRevision shouldBe 3
            memos.refreshCount shouldBe 0
            repo.lastResolutions!!.any { it.kind == RemoteSyncConflictResolution.KIND_SKIP_FOR_NOW } shouldBe true
        }

        test("MERGE_TEXT submits merged_body with merged content") {
            val repo =
                FakeRemoteSyncCenterRepository(
                    backend = RemoteSyncBackendLabel.S3,
                    pages =
                        listOf(
                            page(
                                revision = 1,
                                items = listOf(openMarkdown("memos/m.md")),
                            ),
                        ),
                    markdownBodies =
                        mapOf(
                            // Proven mergeable anchor insertion from SyncConflictTextMergeTest.
                            "memos/m.md" to ("start\nlocal\nmiddle\nend" to "start\nmiddle\nremote\nend"),
                        ),
                    postResolvePages = listOf(page(revision = 2, items = emptyList())),
                )
            val useCase = RemoteSyncConflictDialogUseCase(repo, RecordingMemoMutationRepository())
            val session = useCase.loadOpenSession("/ws").shouldNotBeNull()

            useCase.resolve(
                session = session,
                resolution =
                    SyncConflictResolution(
                        mapOf("memos/m.md" to SyncConflictResolutionChoice.MERGE_TEXT),
                    ),
            )

            val submitted = repo.lastResolutions!!.single()
            submitted.path shouldBe "memos/m.md"
            submitted.kind shouldBe RemoteSyncConflictResolution.KIND_MERGED_BODY
            submitted.mergedBody.shouldNotBeNull()
        }
    }
}

private fun openMarkdown(path: String): RemoteSyncConflictPath =
    RemoteSyncConflictPath(
        path = path,
        kind = "markdown",
        localDigest = "l",
        remoteDigest = "r",
        baselineDigest = "b",
        remoteTokenPresent = true,
        localArtifactRef = "art-l",
        remoteArtifactRef = "art-r",
        baselineArtifactRef = "art-b",
        status = RemoteSyncConflictPathStatus.Open,
    )

private fun openBinary(path: String): RemoteSyncConflictPath =
    RemoteSyncConflictPath(
        path = path,
        kind = "binary",
        localDigest = "l",
        remoteDigest = "r",
        baselineDigest = "b",
        remoteTokenPresent = true,
        localArtifactRef = null,
        remoteArtifactRef = null,
        status = RemoteSyncConflictPathStatus.Open,
    )

private fun resolvedMarkdown(path: String): RemoteSyncConflictPath =
    openMarkdown(path).copy(status = RemoteSyncConflictPathStatus.ResolvedKeepLocal)

private fun page(
    revision: Long,
    items: List<RemoteSyncConflictPath>,
    nextCursor: Int? = null,
): RemoteSyncConflictPage =
    RemoteSyncConflictPage(
        sessionId = "sess-1",
        conflictRevision = revision,
        items = items,
        nextCursor = nextCursor,
    )

private class RecordingMemoMutationRepository : MemoMutationRepository {
    var refreshCount: Int = 0
        private set

    override suspend fun refreshMemos() {
        refreshCount += 1
    }

    override suspend fun saveMemo(
        content: String,
        timestamp: Long,
        geoLocation: String?,
    ): com.lomo.domain.model.Memo = error("unused")

    override suspend fun updateMemo(
        memo: com.lomo.domain.model.Memo,
        newContent: String,
    ) = error("unused")

    override suspend fun deleteMemo(memo: com.lomo.domain.model.Memo) = error("unused")

    override suspend fun restoreMemoRevision(
        currentMemo: com.lomo.domain.model.Memo,
        revisionId: String,
    ) = error("unused")

    override suspend fun setMemoPinned(
        memoId: String,
        pinned: Boolean,
    ) = error("unused")
}

private class FakeRemoteSyncCenterRepository(
    private val backend: RemoteSyncBackendLabel = RemoteSyncBackendLabel.None,
    private val pages: List<RemoteSyncConflictPage> = emptyList(),
    private val postResolvePages: List<RemoteSyncConflictPage>? = null,
    private val markdownBodies: Map<String, Pair<String?, String?>> = emptyMap(),
) : RemoteSyncCenterRepository {
    var lastResolveExpectedRevision: Long? = null
        private set
    var lastResolutions: List<RemoteSyncConflictResolution>? = null
        private set

    private var listPhase: ListPhase = ListPhase.Initial

    override fun configSummary(workspaceRoot: String): RemoteSyncConfigSummary =
        RemoteSyncConfigSummary(
            backend = backend,
            attentionCount = pages.firstOrNull()?.items?.count { it.status == RemoteSyncConflictPathStatus.Open } ?: 0,
            lastVerifiedAtEpochMillis = null,
            schedulePolicyLabel = null,
        )

    override fun sessionProgress(workspaceRoot: String): RemoteSyncSessionProgress =
        RemoteSyncSessionProgress(
            phase = RemoteSyncSessionPhase.ConflictOpen,
            completedActions = 0,
            totalActions = null,
            canCancel = false,
        )

    override fun listConflicts(
        workspaceRoot: String,
        cursor: Int,
        limit: Int,
    ): RemoteSyncConflictPage {
        val source =
            when (listPhase) {
                ListPhase.Initial -> pages
                ListPhase.PostResolve -> postResolvePages ?: pages
            }
        // Single-page fakes for dialog contract.
        return source.firstOrNull()
            ?: RemoteSyncConflictPage(
                sessionId = "sess-empty",
                conflictRevision = 0,
                items = emptyList(),
                nextCursor = null,
            )
    }

    override fun resolveConflicts(
        workspaceRoot: String,
        expectedRevision: Long,
        resolutions: List<RemoteSyncConflictResolution>,
    ): RemoteSyncConflictResolveResult {
        lastResolveExpectedRevision = expectedRevision
        lastResolutions = resolutions
        listPhase = ListPhase.PostResolve
        return RemoteSyncConflictResolveResult(
            sessionId = "sess-1",
            conflictRevision = expectedRevision + 1,
            appliedPaths = resolutions.map { it.path },
        )
    }

    override fun markdownConflictFacts(
        workspaceRoot: String,
        path: RemoteSyncConflictPath,
        mergedDraft: String?,
    ): RemoteSyncMarkdownConflictFacts {
        val bodies = markdownBodies[path.path]
        return RemoteSyncMarkdownConflictFacts(
            path = path.path,
            baseDigest = path.baselineDigest,
            localDigest = path.localDigest,
            remoteDigest = path.remoteDigest,
            baseBody = null,
            localBody = bodies?.first,
            remoteBody = bodies?.second,
            mergedDraft = mergedDraft,
        )
    }

    override fun binaryConflictFacts(
        workspaceRoot: String,
        path: RemoteSyncConflictPath,
    ): RemoteSyncBinaryConflictFacts =
        RemoteSyncBinaryConflictFacts(
            path = path.path,
            mimeType = null,
            sizeBytes = null,
            localDigest = path.localDigest,
            remoteDigest = path.remoteDigest,
            baselineDigest = path.baselineDigest,
            sourceLabel = "test",
        )

    private enum class ListPhase {
        Initial,
        PostResolve,
    }
}
