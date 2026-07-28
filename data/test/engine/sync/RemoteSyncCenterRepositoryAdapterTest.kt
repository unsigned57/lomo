package com.lomo.data.engine.sync

/*
 * Behavior Contract:
 * - Unit under test: RemoteSyncCenterRepositoryAdapter (Wave-4 dark host adapter)
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: map RemoteSyncRepository BoltFFI facts → domain RemoteSyncCenterRepository;
 *   load markdown base/local/remote bodies from durable artifact refs when present; binary
 *   detail never invents text body preview; boundary failures map to RemoteSyncCenterFailure.
 *
 * Scenarios:
 * - Given a data conflict page with digests + artifact refs, when listConflicts runs, then
 *   domain page maps revision/cursor/status without inventing body bytes on the list wire.
 * - Given keep_local resolution, when resolveConflicts runs, then data repo receives mapped
 *   resolutions and domain result advances revision / applied paths.
 * - Given RemoteSyncBoundaryFailure stale revision, when resolveConflicts runs, then
 *   RemoteSyncCenterFailure preserves category/code/retryDisposition.
 * - Given markdown path with local/remote/baseline artifact refs and UTF-8 bodies, when
 *   markdownConflictFacts runs, then base/local/remote bodies are the decoded text.
 * - Given markdown path with missing artifact ref, when markdownConflictFacts runs, then that
 *   body remains null (digest-only honesty).
 * - Given binary path, when binaryConflictFacts runs, then digests/source are set and no text
 *   body fields exist on the facts type (MIME/size null is honest).
 * - Given invalid UTF-8 artifact bytes, when markdownConflictFacts runs, then center failure
 *   code conflict_artifact_invalid_utf8 (fail closed).
 *
 * Observable outcomes: domain page/result/detail fields; fake remote last request; failure codes.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=data
 *   --include-classes='com.lomo.data.engine.sync.RemoteSyncCenterRepositoryAdapterTest'
 * - RED: domain port had no data adapter; markdown bodies always null in shell helpers.
 * - GREEN: list/resolve/stale mapping + markdown real bodies + binary no-text + UTF-8 fail-closed.
 *
 * Excludes:
 * - Real JNI / process vault / durable .lomo/sync (covered by rust sync_ffi_contract).
 * - Production DI / navigation / ViewModelModule (P5-13).
 * - Compose rendering (app feature shell).
 */

import com.lomo.domain.model.RemoteSyncBackendLabel
import com.lomo.domain.model.RemoteSyncCenterFailure
import com.lomo.domain.model.RemoteSyncConflictPath as DomainConflictPath
import com.lomo.domain.model.RemoteSyncConflictPathStatus as DomainPathStatus
import com.lomo.domain.model.RemoteSyncConflictResolution as DomainConflictResolution
import com.lomo.domain.model.RemoteSyncSessionPhase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf


private class FakeRemoteSyncRepository : RemoteSyncRepository {
    var lastCursor: Int? = null
    var lastLimit: Int? = null
    var lastExpectedRevision: Long? = null
    var lastResolutions: List<RemoteSyncConflictResolution>? = null
    var listPage: RemoteSyncConflictPage =
        RemoteSyncConflictPage(
            sessionId = "session-1",
            conflictRevision = 3L,
            items =
                listOf(
                    RemoteSyncConflictPath(
                        path = "memo/a.md",
                        kind = "markdown",
                        localDigest = "aa".repeat(32),
                        remoteDigest = "bb".repeat(32),
                        baselineDigest = "00".repeat(32),
                        remoteTokenPresent = true,
                        localArtifactRef = "art-local",
                        remoteArtifactRef = "art-remote",
                        baselineArtifactRef = "art-base",
                        status = RemoteSyncConflictPathStatus.Open,
                    ),
                    RemoteSyncConflictPath(
                        path = "media/x.bin",
                        kind = "binary",
                        localDigest = "cc".repeat(32),
                        remoteDigest = "dd".repeat(32),
                        baselineDigest = "ee".repeat(32),
                        remoteTokenPresent = false,
                        localArtifactRef = "bin-local",
                        remoteArtifactRef = "bin-remote",
                        baselineArtifactRef = null,
                        status = RemoteSyncConflictPathStatus.Open,
                    ),
                ),
            nextCursor = 100,
        )
    var resolveResult: RemoteSyncConflictResolveResult =
        RemoteSyncConflictResolveResult(
            sessionId = "session-1",
            conflictRevision = 4L,
            appliedPaths = listOf("memo/a.md"),
        )
    var resolveError: RemoteSyncBoundaryFailure? = null

    override fun listConflicts(
        workspaceRoot: String,
        cursor: Int,
        limit: Int,
    ): RemoteSyncConflictPage {
        lastCursor = cursor
        lastLimit = limit
        return listPage
    }

    override fun resolveConflicts(
        workspaceRoot: String,
        expectedRevision: Long,
        resolutions: List<RemoteSyncConflictResolution>,
    ): RemoteSyncConflictResolveResult {
        lastExpectedRevision = expectedRevision
        lastResolutions = resolutions
        resolveError?.let { throw it }
        return resolveResult
    }

    override fun issueSecretLease(
        secretBytes: ByteArray,
        ttlMillis: Long,
    ): RemoteSyncSecretLease = error("not used")

    override fun probeSecretLease(leaseId: String): Int = error("not used")

    override fun revokeSecretLease(leaseId: String) = error("not used")

    override fun retryHintFromDispositionName(name: String): RemoteSyncRetryHint =
        error("not used")

    override fun inspectCyclePlan(workspaceRoot: String): RemoteSyncCyclePlanSummary =
        error("not used by Sync Center adapter")

    override fun runCycle(request: RemoteSyncCycleRequest): RemoteSyncCyclePlanSummary =
        error("not used by Sync Center adapter")
}

private class MapConflictArtifactSource(
    private val bodies: MutableMap<String, ByteArray> = mutableMapOf(),
) : ConflictArtifactSource {
    var lastRef: String? = null

    fun put(
        ref: String,
        body: String,
    ) {
        bodies[ref] = body.toByteArray(Charsets.UTF_8)
    }

    fun putBytes(
        ref: String,
        bytes: ByteArray,
    ) {
        bodies[ref] = bytes
    }

    override fun readArtifact(
        workspaceRoot: String,
        artifactRef: String,
    ): ByteArray {
        lastRef = artifactRef
        return bodies[artifactRef]
            ?: throw RemoteSyncBoundaryFailure(
                category = "storage",
                code = "conflict_artifact_open_failed",
                retryDisposition = "never",
                diagnostic = "missing artifact $artifactRef",
            )
    }
}

class RemoteSyncCenterRepositoryAdapterTest : FunSpec({
    test("listConflicts maps digests refs and status without inventing body bytes") {
        val remote = FakeRemoteSyncRepository()
        val artifacts = MapConflictArtifactSource()
        val adapter = RemoteSyncCenterRepositoryAdapter(remote, artifacts)

        val page = adapter.listConflicts(workspaceRoot = "/ws", cursor = 0, limit = 10)

        remote.lastCursor shouldBe 0
        remote.lastLimit shouldBe 10
        page.sessionId shouldBe "session-1"
        page.conflictRevision shouldBe 3L
        page.nextCursor shouldBe 100
        page.items.size shouldBe 2
        val md = page.items.first { it.path == "memo/a.md" }
        md.kind shouldBe "markdown"
        md.status shouldBe DomainPathStatus.Open
        md.localArtifactRef shouldBe "art-local"
        md.baselineArtifactRef shouldBe "art-base"
        md.remoteTokenPresent shouldBe true
        // List wire must not invent body text fields.
        md.toString() shouldNotContain "localBody"
        page.toString() shouldNotContain "mergedBody"
    }

    test("resolveConflicts maps keep_local and advances domain revision") {
        val remote = FakeRemoteSyncRepository()
        val artifacts = MapConflictArtifactSource()
        val adapter = RemoteSyncCenterRepositoryAdapter(remote, artifacts)

        val result =
            adapter.resolveConflicts(
                workspaceRoot = "/ws",
                expectedRevision = 3L,
                resolutions =
                    listOf(
                        DomainConflictResolution(
                            path = "memo/a.md",
                            kind = DomainConflictResolution.KIND_KEEP_LOCAL,
                        ),
                    ),
            )

        remote.lastExpectedRevision shouldBe 3L
        remote.lastResolutions.shouldNotBeNull().single().kind shouldBe "keep_local"
        result.conflictRevision shouldBe 4L
        result.appliedPaths shouldContainExactly listOf("memo/a.md")
    }

    test("stale revision boundary maps to RemoteSyncCenterFailure") {
        val remote = FakeRemoteSyncRepository()
        remote.resolveError =
            RemoteSyncBoundaryFailure(
                category = "conflict",
                code = "conflict_revision_stale",
                retryDisposition = "after_user_action",
                diagnostic = "expected conflict revision is stale",
            )
        val adapter = RemoteSyncCenterRepositoryAdapter(remote, MapConflictArtifactSource())

        val failure =
            shouldThrow<RemoteSyncCenterFailure> {
                adapter.resolveConflicts(
                    workspaceRoot = "/ws",
                    expectedRevision = 3L,
                    resolutions =
                        listOf(
                            DomainConflictResolution(
                                path = "memo/a.md",
                                kind = DomainConflictResolution.KIND_KEEP_LOCAL,
                            ),
                        ),
                )
            }
        failure.category shouldBe "conflict"
        failure.code shouldBe "conflict_revision_stale"
        failure.retryDisposition shouldBe "after_user_action"
        failure.shouldBeInstanceOf<RemoteSyncCenterFailure>()
    }

    test("markdownConflictFacts loads real base local remote bodies when artifacts present") {
        val remote = FakeRemoteSyncRepository()
        val artifacts = MapConflictArtifactSource()
        artifacts.put("art-base", "# base\n")
        artifacts.put("art-local", "# local side\n")
        artifacts.put("art-remote", "# remote side\n")
        val adapter = RemoteSyncCenterRepositoryAdapter(remote, artifacts)
        val path =
            DomainConflictPath(
                path = "memo/a.md",
                kind = "markdown",
                localDigest = "l",
                remoteDigest = "r",
                baselineDigest = "b",
                remoteTokenPresent = true,
                localArtifactRef = "art-local",
                remoteArtifactRef = "art-remote",
                baselineArtifactRef = "art-base",
                status = DomainPathStatus.Open,
            )

        val facts =
            adapter.markdownConflictFacts(
                workspaceRoot = "/ws",
                path = path,
                mergedDraft = "draft-merge",
            )

        facts.baseBody shouldBe "# base\n"
        facts.localBody shouldBe "# local side\n"
        facts.remoteBody shouldBe "# remote side\n"
        facts.mergedDraft shouldBe "draft-merge"
        facts.baseDigest shouldBe "b"
        facts.localDigest shouldBe "l"
        facts.remoteDigest shouldBe "r"
    }

    test("markdownConflictFacts leaves body null when artifact ref absent") {
        val remote = FakeRemoteSyncRepository()
        val artifacts = MapConflictArtifactSource()
        val adapter = RemoteSyncCenterRepositoryAdapter(remote, artifacts)
        val path =
            DomainConflictPath(
                path = "memo/a.md",
                kind = "markdown",
                localDigest = "l",
                remoteDigest = "r",
                baselineDigest = "b",
                remoteTokenPresent = false,
                localArtifactRef = null,
                remoteArtifactRef = "art-remote",
                baselineArtifactRef = null,
                status = DomainPathStatus.Open,
            )
        artifacts.put("art-remote", "only remote\n")

        val facts = adapter.markdownConflictFacts("/ws", path, mergedDraft = null)

        facts.baseBody.shouldBeNull()
        facts.localBody.shouldBeNull()
        facts.remoteBody shouldBe "only remote\n"
    }

    test("binaryConflictFacts never invents text body preview") {
        val remote = FakeRemoteSyncRepository()
        val artifacts = MapConflictArtifactSource()
        artifacts.putBytes("bin-local", byteArrayOf(0x00, 0x01, 0xFF.toByte()))
        val adapter = RemoteSyncCenterRepositoryAdapter(remote, artifacts)
        val path =
            DomainConflictPath(
                path = "media/x.bin",
                kind = "binary",
                localDigest = "ld",
                remoteDigest = "rd",
                baselineDigest = "bd",
                remoteTokenPresent = false,
                localArtifactRef = "bin-local",
                remoteArtifactRef = "bin-remote",
                baselineArtifactRef = null,
                status = DomainPathStatus.Open,
            )

        val facts = adapter.binaryConflictFacts(workspaceRoot = "/ws", path = path)

        facts.path shouldBe "media/x.bin"
        facts.localDigest shouldBe "ld"
        facts.remoteDigest shouldBe "rd"
        facts.baselineDigest shouldBe "bd"
        facts.mimeType.shouldBeNull()
        facts.sizeBytes.shouldBeNull()
        facts.sourceLabel shouldBe "remote_sync"
        // No text body fields on binary facts type.
        facts.toString() shouldNotContain "Body"
        // Artifact source must not be consulted for binary text invention.
        artifacts.lastRef.shouldBeNull()
    }

    test("invalid utf8 artifact fails closed for markdown body load") {
        val remote = FakeRemoteSyncRepository()
        val artifacts = MapConflictArtifactSource()
        artifacts.putBytes("art-local", byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        val adapter = RemoteSyncCenterRepositoryAdapter(remote, artifacts)
        val path =
            DomainConflictPath(
                path = "memo/a.md",
                kind = "markdown",
                localDigest = "l",
                remoteDigest = null,
                baselineDigest = null,
                remoteTokenPresent = false,
                localArtifactRef = "art-local",
                remoteArtifactRef = null,
                baselineArtifactRef = null,
                status = DomainPathStatus.Open,
            )

        val failure =
            shouldThrow<RemoteSyncCenterFailure> {
                adapter.markdownConflictFacts("/ws", path, mergedDraft = null)
            }
        failure.code shouldBe "conflict_artifact_invalid_utf8"
        failure.category shouldBe "validation"
    }

    test("config and session presentation shells use injected providers") {
        val remote = FakeRemoteSyncRepository()
        val artifacts = MapConflictArtifactSource()
        val adapter =
            RemoteSyncCenterRepositoryAdapter(
                remoteSync = remote,
                artifactSource = artifacts,
                configSummaryProvider = {
                    com.lomo.domain.model.RemoteSyncConfigSummary(
                        backend = RemoteSyncBackendLabel.Git,
                        attentionCount = 2,
                        lastVerifiedAtEpochMillis = 99L,
                        schedulePolicyLabel = "interval_1h",
                    )
                },
                sessionProgressProvider = {
                    com.lomo.domain.model.RemoteSyncSessionProgress(
                        phase = RemoteSyncSessionPhase.ConflictOpen,
                        completedActions = 1,
                        totalActions = 5,
                        canCancel = true,
                    )
                },
            )

        adapter.configSummary("/ws").backend shouldBe RemoteSyncBackendLabel.Git
        adapter.sessionProgress("/ws").phase shouldBe RemoteSyncSessionPhase.ConflictOpen
    }
})
