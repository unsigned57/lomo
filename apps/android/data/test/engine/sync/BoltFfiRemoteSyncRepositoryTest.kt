package com.lomo.data.engine.sync

/*
 * Behavior Contract:
 * - Unit under test: BoltFfiRemoteSyncRepository + KeystoreRustSyncSecretSupplier (dark P5-09)
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: map coarse BoltFFI sync free-function DTOs to host facts; fail-closed boundaries;
 *   secret supplier journals lease ids only (never plaintext); no fixed three-retry on disposition map.
 *
 * Scenarios:
 * - Given a bridge conflict page with digests + remote token presence, when listConflicts runs,
 *   then domain page fields map (revision/cursor as Long/Int) without inventing body bytes.
 * - Given a keep_local resolution batch, when resolveConflicts runs, then bridge receives mapped
 *   DTOs and applied paths / advanced revision map.
 * - Given stale/invalid boundary failure on bridge, when resolveConflicts runs, then
 *   RemoteSyncBoundaryFailure preserves category/code/retryDisposition (no plaintext diagnostic
 *   secret assumption).
 * - Given empty resolution batch or non-positive limit, when repository methods run, then Kotlin
 *   edge require fails before bridge call.
 * - Given secret issue→probe→revoke via fake bridge, when supplier issues a lease, then only lease
 *   id is returned and source plaintext is not stored as lease id.
 * - Given disposition names never / after_user_action / transient, when retryHint maps, then
 *   disposition enums match (no maxAttempts=3 policy embedded).
 *
 * Observable outcomes: RemoteSyncConflictPage / ResolveResult / SecretLease / RetryHint fields;
 * last bridge request fields; require / RemoteSyncBoundaryFailure types.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=data --include-classes='com.lomo.data.engine.sync.BoltFfiRemoteSyncRepositoryTest'
 * - RED: dark Kotlin RemoteSyncRepository surface was OPEN / untested before this host contract.
 * - GREEN: list/resolve/stale/oversize-edge mapping + secret lease id-only + disposition map.
 *
 * Excludes:
 * - Real JNI / process vault / durable .lomo/sync (covered by rust sync_ffi_contract).
 * - Production DI / WorkManager registration (P5-13).
 * - Sync Center Compose UI (P5-10).
 */

import com.lomo.nativebridge.EngineError
import com.lomo.nativebridge.EngineFailure
import com.lomo.nativebridge.SyncConflictPageDto as BridgeConflictPage
import com.lomo.nativebridge.SyncConflictPathDto as BridgeConflictPath
import com.lomo.nativebridge.SyncConflictPathStatusDto as BridgePathStatus
import com.lomo.nativebridge.SyncConflictResolutionDto as BridgeResolution
import com.lomo.nativebridge.SyncConflictResolveResultDto as BridgeResolveResult
import com.lomo.nativebridge.SyncCyclePlanSummaryDto as BridgeCyclePlan
import com.lomo.nativebridge.SyncRetryDispositionDto as BridgeRetryDisposition
import com.lomo.nativebridge.SyncRetryHintDto as BridgeRetryHint
import com.lomo.nativebridge.SyncSecretLeaseDto as BridgeSecretLease
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain

private class RecordingSyncNativeBridge : SyncNativeBridge {
    var lastWorkspaceRoot: String? = null
    var lastCursor: UInt? = null
    var lastLimit: UInt? = null
    var lastExpectedRevision: ULong? = null
    var lastResolutions: List<BridgeResolution>? = null
    var lastSecretBytes: ByteArray? = null
    var lastTtlMillis: ULong? = null
    var lastProbeLeaseId: String? = null
    var lastRevokeLeaseId: String? = null
    var lastDispositionName: String? = null

    var listPage: BridgeConflictPage =
        BridgeConflictPage(
            sessionId = "session-1",
            conflictRevision = 1uL,
            items =
                listOf(
                    BridgeConflictPath(
                        path = "memo/a.md",
                        kind = "markdown",
                        localDigest = "aa".repeat(32),
                        remoteDigest = "bb".repeat(32),
                        baselineDigest = "00".repeat(32),
                        remoteTokenPresent = true,
                        localArtifactRef = "art-local",
                        remoteArtifactRef = "art-remote",
                        baselineArtifactRef = "art-base",
                        status = BridgePathStatus.OPEN,
                    ),
                ),
            nextCursor = 1u,
        )
    var resolveResult: BridgeResolveResult =
        BridgeResolveResult(
            sessionId = "session-1",
            conflictRevision = 2uL,
            appliedPaths = listOf("memo/a.md"),
        )
    var listError: EngineError? = null
    var resolveError: EngineError? = null
    var issueError: EngineError? = null
    var inspectError: EngineError? = null
    var nextLeaseId: String = "lease-dark-test-001"
    var probeLength: UInt = 5u
    var lastInspectWorkspaceRoot: String? = null
    var cyclePlan: BridgeCyclePlan =
        BridgeCyclePlan(
            sessionId = "session-cycle",
            sessionKind = "incremental",
            sessionRevision = 1uL,
            baselineEstablished = false,
            ensurePresentCount = 0u,
            ensureAbsentCount = 0u,
            pullPresentCount = 0u,
            openConflictCount = 0u,
            openConflictPaths = 0u,
            conflictRevision = null,
            retryDisposition = "after_user_action",
        )

    override fun listConflicts(
        workspaceRoot: String,
        cursor: UInt,
        limit: UInt,
    ): BridgeConflictPage {
        lastWorkspaceRoot = workspaceRoot
        lastCursor = cursor
        lastLimit = limit
        listError?.let { throw it }
        return listPage
    }

    override fun resolveConflicts(
        workspaceRoot: String,
        expectedRevision: ULong,
        resolutions: List<BridgeResolution>,
    ): BridgeResolveResult {
        lastWorkspaceRoot = workspaceRoot
        lastExpectedRevision = expectedRevision
        lastResolutions = resolutions
        resolveError?.let { throw it }
        return resolveResult
    }

    override fun issueSecretLease(
        secretBytes: ByteArray,
        ttlMillis: ULong,
    ): BridgeSecretLease {
        lastSecretBytes = secretBytes.copyOf()
        lastTtlMillis = ttlMillis
        issueError?.let { throw it }
        return BridgeSecretLease(leaseId = nextLeaseId)
    }

    override fun probeSecretLease(leaseId: String): UInt {
        lastProbeLeaseId = leaseId
        return probeLength
    }

    override fun revokeSecretLease(leaseId: String) {
        lastRevokeLeaseId = leaseId
    }

    override fun readConflictArtifact(
        workspaceRoot: String,
        artifactRef: String,
    ): ByteArray {
        lastWorkspaceRoot = workspaceRoot
        return ByteArray(0)
    }

    override fun retryDispositionFromName(name: String): BridgeRetryHint {
        lastDispositionName = name
        val disposition =
            when (name) {
                "never" -> BridgeRetryDisposition.NEVER
                "after_user_action" -> BridgeRetryDisposition.AFTER_USER_ACTION
                "transient" -> BridgeRetryDisposition.TRANSIENT
                else ->
                    throw EngineError.Failure(
                        EngineFailure(
                            category = "validation",
                            code = "sync_ffi_retry_disposition_invalid",
                            retryDisposition = "never",
                            operationId = null,
                            jobId = null,
                            diagnostic = "retry disposition must be never|after_user_action|transient",
                        ),
                    )
            }
        return BridgeRetryHint(disposition = disposition, retryAfterMillis = null)
    }

    override fun inspectCyclePlan(workspaceRoot: String): BridgeCyclePlan {
        lastInspectWorkspaceRoot = workspaceRoot
        inspectError?.let { throw it }
        return cyclePlan
    }

    var lastRunCycleBackendKind: String? = null
    var lastRunCycleLeaseId: String? = null
    var runCycleError: EngineError? = null

    override fun runCycle(
        workspaceRoot: String,
        backendKind: String,
        endpointUrl: String,
        usernameOrAccessKey: String,
        bucket: String,
        prefix: String,
        region: String,
        remoteDatasetId: String,
        secretLeaseId: String,
        applyRemote: Boolean,
    ): BridgeCyclePlan {
        lastInspectWorkspaceRoot = workspaceRoot
        lastRunCycleBackendKind = backendKind
        lastRunCycleLeaseId = secretLeaseId
        runCycleError?.let { throw it }
        return cyclePlan
    }
}

private class MemorySecretMaterialSource(
    private val secrets: MutableMap<String, ByteArray> = mutableMapOf(),
) : SecretMaterialSource {
    override fun readSecretBytes(fieldKey: String): ByteArray? = secrets[fieldKey]?.copyOf()

    fun put(
        fieldKey: String,
        value: ByteArray,
    ) {
        secrets[fieldKey] = value.copyOf()
    }
}

class BoltFfiRemoteSyncRepositoryTest : FunSpec({
    test("listConflicts maps digests status and token presence without body bytes") {
        val bridge = RecordingSyncNativeBridge()
        val repository = BoltFfiRemoteSyncRepository(bridge)

        val page = repository.listConflicts(workspaceRoot = "/ws", cursor = 0, limit = 10)

        bridge.lastWorkspaceRoot shouldBe "/ws"
        bridge.lastCursor shouldBe 0u
        bridge.lastLimit shouldBe 10u
        page.sessionId shouldBe "session-1"
        page.conflictRevision shouldBe 1L
        page.nextCursor shouldBe 1
        page.items.size shouldBe 1
        val item = page.items.single()
        item.path shouldBe "memo/a.md"
        item.kind shouldBe "markdown"
        item.status shouldBe RemoteSyncConflictPathStatus.Open
        item.remoteTokenPresent shouldBe true
        item.localDigest shouldBe "aa".repeat(32)
        item.remoteDigest shouldBe "bb".repeat(32)
        // List surface must not invent text body preview fields.
        item.toString() shouldNotContain "mergedBody"
    }

    test("resolveConflicts keep_local advances revision via bridge mapping") {
        val bridge = RecordingSyncNativeBridge()
        val repository = BoltFfiRemoteSyncRepository(bridge)

        val result =
            repository.resolveConflicts(
                workspaceRoot = "/ws",
                expectedRevision = 1L,
                resolutions =
                    listOf(
                        RemoteSyncConflictResolution(
                            path = "memo/a.md",
                            kind = "keep_local",
                        ),
                    ),
            )

        bridge.lastExpectedRevision shouldBe 1uL
        bridge.lastResolutions shouldBe
            listOf(
                BridgeResolution(path = "memo/a.md", kind = "keep_local", mergedBody = null),
            )
        result.sessionId shouldBe "session-1"
        result.conflictRevision shouldBe 2L
        result.appliedPaths shouldContainExactly listOf("memo/a.md")
    }

    test("stale revision boundary failure maps category and code") {
        val bridge = RecordingSyncNativeBridge()
        bridge.resolveError =
            EngineError.Failure(
                EngineFailure(
                    category = "conflict",
                    code = "conflict_revision_stale",
                    retryDisposition = "after_user_action",
                    operationId = null,
                    jobId = null,
                    diagnostic = "expected conflict revision is stale",
                ),
            )
        val repository = BoltFfiRemoteSyncRepository(bridge)

        val failure =
            shouldThrow<RemoteSyncBoundaryFailure> {
                repository.resolveConflicts(
                    workspaceRoot = "/ws",
                    expectedRevision = 1L,
                    resolutions =
                        listOf(
                            RemoteSyncConflictResolution(path = "memo/a.md", kind = "keep_local"),
                        ),
                )
            }

        failure.category shouldBe "conflict"
        failure.code shouldBe "conflict_revision_stale"
        failure.retryDisposition shouldBe "after_user_action"
    }

    test("empty resolution batch fails closed before bridge") {
        val bridge = RecordingSyncNativeBridge()
        val repository = BoltFfiRemoteSyncRepository(bridge)

        shouldThrow<IllegalArgumentException> {
            repository.resolveConflicts(
                workspaceRoot = "/ws",
                expectedRevision = 1L,
                resolutions = emptyList(),
            )
        }
        bridge.lastResolutions.shouldBeNull()
    }

    test("non-positive page limit fails closed before bridge") {
        val bridge = RecordingSyncNativeBridge()
        val repository = BoltFfiRemoteSyncRepository(bridge)

        shouldThrow<IllegalArgumentException> {
            repository.listConflicts(workspaceRoot = "/ws", cursor = 0, limit = 0)
        }
        bridge.lastLimit.shouldBeNull()
    }

    test("oversize secret boundary failure maps resource_limit code") {
        val bridge = RecordingSyncNativeBridge()
        bridge.issueError =
            EngineError.Failure(
                EngineFailure(
                    category = "resource_limit",
                    code = "sync_ffi_secret_too_large",
                    retryDisposition = "never",
                    operationId = null,
                    jobId = null,
                    diagnostic = "secret material exceeds the 64 KiB lease limit",
                ),
            )
        val repository = BoltFfiRemoteSyncRepository(bridge)

        val failure =
            shouldThrow<RemoteSyncBoundaryFailure> {
                repository.issueSecretLease(secretBytes = ByteArray(8) { 1 }, ttlMillis = 1_000)
            }
        failure.category shouldBe "resource_limit"
        failure.code shouldBe "sync_ffi_secret_too_large"
    }

    test("secret lease round trip never uses plaintext as lease id") {
        val bridge = RecordingSyncNativeBridge()
        bridge.nextLeaseId = "lease-opaque-xyz"
        val repository = BoltFfiRemoteSyncRepository(bridge)
        val plaintext = "super-secret-token".toByteArray()

        val lease = repository.issueSecretLease(secretBytes = plaintext, ttlMillis = 60_000)
        lease.leaseId shouldBe "lease-opaque-xyz"
        lease.leaseId shouldNotBe String(plaintext)
        lease.leaseId shouldNotContain "super-secret"

        repository.probeSecretLease(lease.leaseId) shouldBe 5
        bridge.lastProbeLeaseId shouldBe "lease-opaque-xyz"

        repository.revokeSecretLease(lease.leaseId)
        bridge.lastRevokeLeaseId shouldBe "lease-opaque-xyz"
    }

    test("secret supplier issues lease id only and never journals plaintext") {
        val bridge = RecordingSyncNativeBridge()
        bridge.nextLeaseId = "lease-supplier-1"
        val repository = BoltFfiRemoteSyncRepository(bridge)
        val source = MemorySecretMaterialSource()
        source.put("webdav_password", "p@ss-not-on-wire".toByteArray())
        val supplier = KeystoreRustSyncSecretSupplier(source, repository)

        val lease = supplier.issueLease(fieldKey = "webdav_password", ttlMillis = 30_000)
        lease.shouldNotBeNull()
        lease.leaseId shouldBe "lease-supplier-1"
        lease.leaseId shouldNotContain "p@ss"
        // Bridge saw material once for issue; lease wire is id-only.
        bridge.lastSecretBytes shouldBe "p@ss-not-on-wire".toByteArray()
        bridge.lastTtlMillis shouldBe 30_000uL

        source.put("missing_field", ByteArray(0))
        // Unset field → null lease (not error).
        val emptySource = MemorySecretMaterialSource()
        val emptySupplier = KeystoreRustSyncSecretSupplier(emptySource, repository)
        emptySupplier.issueLease("webdav_password", 30_000).shouldBeNull()
    }

    test("retry disposition mapping has no fixed three-retry policy") {
        val bridge = RecordingSyncNativeBridge()
        val repository = BoltFfiRemoteSyncRepository(bridge)

        repository.retryHintFromDispositionName("never").disposition shouldBe
            RemoteSyncRetryDisposition.Never
        repository.retryHintFromDispositionName("after_user_action").disposition shouldBe
            RemoteSyncRetryDisposition.AfterUserAction
        val transient = repository.retryHintFromDispositionName("transient")
        transient.disposition shouldBe RemoteSyncRetryDisposition.Transient
        // Free-function dark slice leaves delay null; host scheduler owns concrete backoff.
        transient.retryAfterMillis.shouldBeNull()

        val invalid =
            shouldThrow<RemoteSyncBoundaryFailure> {
                repository.retryHintFromDispositionName("three_retries")
            }
        invalid.code shouldBe "sync_ffi_retry_disposition_invalid"
    }

    test("runCycle maps composed owner cycle without inventing planner counts") {
        val bridge = RecordingSyncNativeBridge()
        bridge.cyclePlan =
            BridgeCyclePlan(
                sessionId = "session-run",
                sessionKind = "first_takeover",
                sessionRevision = 2uL,
                baselineEstablished = false,
                ensurePresentCount = 3u,
                ensureAbsentCount = 0u,
                pullPresentCount = 0u,
                openConflictCount = 0u,
                openConflictPaths = 0u,
                conflictRevision = null,
                retryDisposition = "after_user_action",
            )
        val repository = BoltFfiRemoteSyncRepository(bridge)

        val summary =
            repository.runCycle(
                RemoteSyncCycleRequest(
                    workspaceRoot = "/ws",
                    backendKind = "hermetic_fake",
                    remoteDatasetId = "ds",
                    applyRemote = false,
                ),
            )

        summary.sessionId shouldBe "session-run"
        summary.ensurePresentCount shouldBe 3
        summary.retryDisposition shouldBe "after_user_action"
        bridge.lastRunCycleBackendKind shouldBe "hermetic_fake"
        bridge.lastInspectWorkspaceRoot shouldBe "/ws"
    }

    test("runCycle blank workspace fail-closed before bridge") {
        val bridge = RecordingSyncNativeBridge()
        val repository = BoltFfiRemoteSyncRepository(bridge)
        shouldThrow<IllegalArgumentException> {
            repository.runCycle(
                RemoteSyncCycleRequest(
                    workspaceRoot = "  ",
                    backendKind = "hermetic_fake",
                    remoteDatasetId = "ds",
                ),
            )
        }
    }

    test("inspectCyclePlan maps Rust-owned cycle summary without inventing planner counts") {
        val bridge = RecordingSyncNativeBridge()
        bridge.cyclePlan =
            BridgeCyclePlan(
                sessionId = "session-cycle",
                sessionKind = "incremental",
                sessionRevision = 2uL,
                baselineEstablished = true,
                ensurePresentCount = 1u,
                ensureAbsentCount = 0u,
                pullPresentCount = 2u,
                openConflictCount = 0u,
                openConflictPaths = 1u,
                conflictRevision = 3uL,
                retryDisposition = "after_user_action",
            )
        val repository = BoltFfiRemoteSyncRepository(bridge)

        val summary = repository.inspectCyclePlan(workspaceRoot = "/ws")

        bridge.lastInspectWorkspaceRoot shouldBe "/ws"
        summary.sessionId shouldBe "session-cycle"
        summary.sessionKind shouldBe "incremental"
        summary.sessionRevision shouldBe 2L
        summary.baselineEstablished shouldBe true
        summary.ensurePresentCount shouldBe 1
        summary.ensureAbsentCount shouldBe 0
        summary.pullPresentCount shouldBe 2
        summary.openConflictCount shouldBe 0
        summary.openConflictPaths shouldBe 1
        summary.conflictRevision shouldBe 3L
        summary.retryDisposition shouldBe "after_user_action"
    }

    test("inspectCyclePlan blank workspace fail-closed before bridge") {
        val bridge = RecordingSyncNativeBridge()
        val repository = BoltFfiRemoteSyncRepository(bridge)

        shouldThrow<IllegalArgumentException> {
            repository.inspectCyclePlan(workspaceRoot = "  ")
        }
        bridge.lastInspectWorkspaceRoot.shouldBeNull()
    }

    test("inspectCyclePlan boundary failure maps category code disposition") {
        val bridge = RecordingSyncNativeBridge()
        bridge.inspectError =
            EngineError.Failure(
                EngineFailure(
                    category = "validation",
                    code = "sync_session_missing",
                    retryDisposition = "never",
                    operationId = null,
                    jobId = null,
                    diagnostic = "durable sync session is required",
                ),
            )
        val repository = BoltFfiRemoteSyncRepository(bridge)

        val failure =
            shouldThrow<RemoteSyncBoundaryFailure> {
                repository.inspectCyclePlan(workspaceRoot = "/ws")
            }
        failure.category shouldBe "validation"
        failure.code shouldBe "sync_session_missing"
        failure.retryDisposition shouldBe "never"
    }
})
