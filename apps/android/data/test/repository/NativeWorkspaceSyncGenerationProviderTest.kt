package com.lomo.data.repository

/*
 * Behavior Contract:
 * - Unit under test: NativeWorkspaceSyncGenerationProvider.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: pending-review identity is the Rust WorkspaceGenerationId for the active Direct
 *   workspace; missing Direct root fails closed; the provider never hashes a path/URI.
 *
 * Scenarios:
 * - Given a Direct workspace root, when activeGeneration runs, then the value is the id returned
 *   by RemoteSyncRepository.loadWorkspaceGeneration for that root.
 * - Given no Direct workspace root, when activeGeneration runs, then require fails before any
 *   repository call.
 *
 * Observable outcomes: WorkspaceSyncGeneration.value; last load root; require exception.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=data --include-classes='com.lomo.data.repository.NativeWorkspaceSyncGenerationProviderTest'
 * - RED: DataStoreWorkspaceSyncGenerationProvider returned "sha256:" + path digest.
 *
 * Excludes: JNI / generation.rec codec (native sync_ffi_contract).
 */

import com.lomo.data.engine.media.WorkspaceFilesystemRoot
import com.lomo.data.engine.sync.RemoteSyncConflictPage
import com.lomo.data.engine.sync.RemoteSyncConflictResolveResult
import com.lomo.data.engine.sync.RemoteSyncConflictResolution
import com.lomo.data.engine.sync.RemoteSyncCyclePlanSummary
import com.lomo.data.engine.sync.RemoteSyncCycleRequest
import com.lomo.data.engine.sync.RemoteSyncRepository
import com.lomo.data.engine.sync.RemoteSyncSecretLease
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

private class RecordingGenerationRemoteSync : RemoteSyncRepository {
    var lastGenerationRoot: String? = null
    var generation: String = "ab".repeat(32)

    override fun listConflicts(
        workspaceRoot: String,
        cursor: Int,
        limit: Int,
    ): RemoteSyncConflictPage = error("unused")

    override fun resolveConflicts(
        workspaceRoot: String,
        expectedRevision: Long,
        resolutions: List<RemoteSyncConflictResolution>,
    ): RemoteSyncConflictResolveResult = error("unused")

    override fun issueSecretLease(
        secretBytes: ByteArray,
        ttlMillis: Long,
    ): RemoteSyncSecretLease = error("unused")

    override fun probeSecretLease(leaseId: String): Int = error("unused")

    override fun revokeSecretLease(leaseId: String) = error("unused")

    override fun inspectCyclePlan(workspaceRoot: String): RemoteSyncCyclePlanSummary = error("unused")

    override fun runCycle(request: RemoteSyncCycleRequest): RemoteSyncCyclePlanSummary = error("unused")

    override fun loadWorkspaceGeneration(workspaceRoot: String): String {
        lastGenerationRoot = workspaceRoot
        return generation
    }

    override fun resetControlTree(workspaceRoot: String) = error("unused")
}

class NativeWorkspaceSyncGenerationProviderTest : FunSpec({
    test("activeGeneration returns the Rust fence id for the Direct root") {
        runTest {
            val remote = RecordingGenerationRemoteSync()
            remote.generation = "ef".repeat(32)
            val provider =
                NativeWorkspaceSyncGenerationProvider(
                    workspaceRoot = WorkspaceFilesystemRoot { "/workspaces/notes" },
                    remoteSync = remote,
                )

            provider.activeGeneration().value shouldBe "ef".repeat(32)
            remote.lastGenerationRoot shouldBe "/workspaces/notes"
        }
    }

    test("missing Direct root fails closed without loading a generation") {
        runTest {
            val remote = RecordingGenerationRemoteSync()
            val provider =
                NativeWorkspaceSyncGenerationProvider(
                    workspaceRoot = WorkspaceFilesystemRoot { null },
                    remoteSync = remote,
                )

            shouldThrow<IllegalArgumentException> {
                provider.activeGeneration()
            }
            remote.lastGenerationRoot.shouldBeNull()
        }
    }
})
