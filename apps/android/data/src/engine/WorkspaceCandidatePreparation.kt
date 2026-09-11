package com.lomo.data.engine

import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.EngineRetryDisposition
import com.lomo.domain.model.StorageLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The only owner of an acquired-but-not-yet-committed workspace candidate.
 *
 * A candidate keeps its native adapter, workspace selection, capability token and projection
 * baseline together. This prevents the lifecycle owner from accidentally promoting an adapter with
 * a token or projection revision belonging to another selection.
 */
internal data class ManagedWorkspaceCandidate(
    val adapter: RustEngineAdapter,
    val selection: NativeWorkspaceSelection?,
    val projectionRevision: ULong?,
    val refreshProjection: Boolean,
) {
    init {
        if (selection == null) {
            require(projectionRevision == null) {
                "Bootstrap candidate cannot carry a workspace projection revision"
            }
            require(!refreshProjection) {
                "Bootstrap candidate cannot schedule a projection refresh"
            }
        } else {
            require(projectionRevision != null) {
                "Workspace candidate must carry its projection revision"
            }
        }
    }

    val capabilityToken: String?
        get() = (selection as? NativeWorkspaceSelection.Saf)?.capabilityToken

    val workspaceId: String?
        get() =
            when (val workspace = selection) {
                null -> null
                is NativeWorkspaceSelection.Direct -> "direct:${workspace.rootPath.absolutePath}"
                is NativeWorkspaceSelection.Saf -> workspace.stableWorkspaceId.value
            }

    val refreshCandidate: ProjectionRefreshCandidate?
        get() =
            if (refreshProjection) {
                ProjectionRefreshCandidate(
                    adapter = adapter,
                    projectionRevision = checkNotNull(projectionRevision),
                )
            } else {
                null
            }

    companion object {
        fun bootstrap(adapter: RustEngineAdapter): ManagedWorkspaceCandidate =
            ManagedWorkspaceCandidate(
                adapter = adapter,
                selection = null,
                projectionRevision = null,
                refreshProjection = false,
            )
    }
}

/** Existing authority that has been committed and only needs a background SAF refresh. */
internal data class ProjectionRefreshCandidate(
    val adapter: RustEngineAdapter,
    val projectionRevision: ULong,
)

/**
 * Opens and prepares a workspace candidate without changing the active session authority.
 *
 * Capability registration, projection inspection and candidate cleanup belong here so every
 * acquisition path has the same failure semantics before [ManagedEngineSession] reaches promotion.
 */
internal class WorkspaceCandidatePreparer(
    private val filesDir: File,
    private val capabilityRegistry: CapabilityRegistry,
    private val openAdapter: (NativeEngineOpenRequest) -> RustEngineAdapter,
    private val isContentUri: (String) -> Boolean,
) {
    fun openBootstrap(): ManagedWorkspaceCandidate =
        ManagedWorkspaceCandidate.bootstrap(
            openAdapter(NativeEngineOpenRequest.forAppFilesDir(filesDir)),
        )

    suspend fun prepare(
        location: StorageLocation,
        allowBackgroundRefresh: Boolean,
    ): ManagedWorkspaceCandidate {
        val selection = selectionFor(location)
        val candidate = openWorkspaceAdapter(selection)
        val candidateReadiness = candidate.readiness.value
        if (candidateReadiness is EngineReadiness.Ready) {
            return try {
                val currentProjectionRevision = candidate.storeProjectionRevision()
                if (selection.workspace is NativeWorkspaceSelection.Saf &&
                    !allowBackgroundRefresh && currentProjectionRevision == 0uL
                ) {
                    val rebuiltRevision =
                        withContext(Dispatchers.IO) {
                            candidate.rebuildSafProjectionFromWorkspaceScan().highWaterRevision
                        }
                    ManagedWorkspaceCandidate(
                        adapter = candidate,
                        selection = selection.workspace,
                        projectionRevision = rebuiltRevision,
                        refreshProjection = false,
                    )
                } else {
                    ManagedWorkspaceCandidate(
                        adapter = candidate,
                        selection = selection.workspace,
                        projectionRevision = currentProjectionRevision,
                        refreshProjection = selection.workspace is NativeWorkspaceSelection.Saf,
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failPreparation(candidate, selection.capabilityToken, error)
            }
        }

        // Soft open (Recovery / Opening / Awaiting): never promote; release the candidate.
        val activation =
            WorkspaceActivationException(
                candidateReadiness as? EngineReadiness.ReadOnlyRecovery
                    ?: EngineReadiness.ReadOnlyRecovery(
                        category = EngineFailureCategory.INTERNAL,
                        code = "workspace_open_not_ready",
                        retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                        diagnostic =
                            "Workspace open did not reach Ready " +
                                "(${candidateReadiness::class.simpleName})",
                    ),
            )
        releaseCandidate(candidate, selection.capabilityToken, activation, capabilityRegistry)
        throw activation
    }

    fun rebuildProjectionForRecovery(
        location: StorageLocation,
        batchSize: UInt,
    ): com.lomo.nativebridge.StoreRebuildResult {
        val selection = selectionFor(location)
        val repairAdapter = openWorkspaceAdapter(selection)
        val rebuildResult = runCatching { repairAdapter.startRebuild(batchSize) }
        val closeFailure = runCatching(repairAdapter::close).exceptionOrNull()
        selection.capabilityToken?.let(capabilityRegistry::revoke)
        return rebuildResult.fold(
            onSuccess = { result ->
                if (closeFailure != null) throw closeFailure
                result
            },
            onFailure = { failure ->
                closeFailure?.let(failure::addSuppressed)
                throw failure
            },
        )
    }

    private fun selectionFor(location: StorageLocation): PreparedSelection {
        val raw = location.raw.trim()
        return if (isContentUri(raw)) {
            val token = "cap-${java.util.UUID.randomUUID()}"
            val grant = capabilityRegistry.register(token = token, treeUri = raw)
            PreparedSelection(
                workspace = NativeWorkspaceSelection.Saf(grant),
                capabilityToken = grant.capabilityToken,
            )
        } else {
            PreparedSelection(
                workspace = NativeWorkspaceSelection.Direct(rootPath = File(raw)),
                capabilityToken = null,
            )
        }
    }

    private fun openWorkspaceAdapter(selection: PreparedSelection): RustEngineAdapter =
        runCatching {
            openAdapter(
                NativeEngineOpenRequest
                    .forAppFilesDir(filesDir)
                    .copy(workspace = selection.workspace),
            )
        }.onFailure { selection.capabilityToken?.let(capabilityRegistry::revoke) }
            .getOrThrow()

    private fun failPreparation(
        candidate: RustEngineAdapter,
        capabilityToken: String?,
        error: Exception,
    ): Nothing {
        releaseCandidate(candidate, capabilityToken, error, capabilityRegistry)
        throw recoveryForPreparationFailure(error)?.let(::WorkspaceActivationException) ?: error
    }

    private fun recoveryForPreparationFailure(error: Throwable): EngineReadiness.ReadOnlyRecovery? =
        when (error) {
            is ProjectionRebuildException ->
                EngineReadiness.ReadOnlyRecovery(
                    category = error.failureCategory.toFailureCategory(),
                    code = error.failureCode,
                    retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                    diagnostic = error.message ?: "Workspace projection rebuild failed",
                )
            is ProjectionScanDeadlineExceededException ->
                EngineReadiness.ReadOnlyRecovery(
                    category = EngineFailureCategory.TIMEOUT,
                    code = "projection_scan_deadline_exceeded",
                    retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                    diagnostic = "Workspace projection scan exceeded deadline",
                )
            else -> null
        }

    private data class PreparedSelection(
        val workspace: NativeWorkspaceSelection,
        val capabilityToken: String?,
    )
}
