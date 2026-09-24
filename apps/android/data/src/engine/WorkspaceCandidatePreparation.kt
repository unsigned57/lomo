package com.lomo.data.engine

import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.EngineRetryDisposition
import com.lomo.domain.model.StorageLocation
import kotlinx.coroutines.CancellationException
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
) {
    init {
        if (selection == null) {
            require(projectionRevision == null) {
                "Bootstrap candidate cannot carry a workspace projection revision"
            }
        } else {
            requireNotNull(projectionRevision) {
                "Workspace candidate must carry its projection revision"
            }
        }
    }

    val capabilityToken: String?
        get() =
            when (val workspace = selection) {
                null -> null
                is NativeWorkspaceSelection.Direct -> workspace.capabilityToken
                is NativeWorkspaceSelection.Saf -> workspace.capabilityToken
            }

    val workspaceId: String?
        get() =
            when (val workspace = selection) {
                null -> null
                is NativeWorkspaceSelection.Direct -> workspace.stableWorkspaceId.value
                is NativeWorkspaceSelection.Saf -> workspace.stableWorkspaceId.value
            }

    companion object {
        fun bootstrap(adapter: RustEngineAdapter): ManagedWorkspaceCandidate =
            ManagedWorkspaceCandidate(
                adapter = adapter,
                selection = null,
                projectionRevision = null,
            )
    }
}

/**
 * Opens and prepares a workspace candidate without changing the active session authority.
 *
 * Capability registration, projection inspection and candidate cleanup belong here so every
 * acquisition path has the same failure semantics before [ManagedEngineSession] reaches promotion.
 * Mount reconcile is owned by Rust session open; this type only transcribes the resulting revision.
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

    fun prepare(location: StorageLocation): ManagedWorkspaceCandidate {
        val selection = selectionFor(location)
        val candidate = openWorkspaceAdapter(selection)
        val candidateReadiness = candidate.readiness.value
        if (candidateReadiness is EngineReadiness.Ready) {
            return try {
                val projectionRevision = candidate.storeProjectionRevision()
                candidate.resnapshot()
                val afterInspect = candidate.readiness.value
                if (afterInspect !is EngineReadiness.Ready) {
                    val activation =
                        WorkspaceActivationException(
                            afterInspect as? EngineReadiness.ReadOnlyRecovery
                                ?: workspaceOpenNotReady(afterInspect),
                        )
                    failPreparation(candidate, selection.capabilityToken, activation)
                }
                ManagedWorkspaceCandidate(
                    adapter = candidate,
                    selection = selection.workspace,
                    projectionRevision = projectionRevision,
                )
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
            val grant = capabilityRegistry.register(treeUri = raw)
            PreparedSelection(
                workspace = NativeWorkspaceSelection.Saf(grant),
                capabilityToken = grant.capabilityToken,
            )
        } else {
            val grant = capabilityRegistry.registerDirect(rootPath = File(raw))
            PreparedSelection(
                workspace = NativeWorkspaceSelection.Direct(grant),
                capabilityToken = grant.capabilityToken,
            )
        }
    }

    private fun openWorkspaceAdapter(selection: PreparedSelection): RustEngineAdapter =
        runCatching {
            // Stage root follows the candidate selection, not the currently active workspace: a
            // Direct workspace stages beside its root while SAF workspaces share the app-private
            // host stage root.
            val stageRoot =
                when (val workspace = selection.workspace) {
                    is NativeWorkspaceSelection.Direct -> workspace.rootPath
                    is NativeWorkspaceSelection.Saf ->
                        File(filesDir, com.lomo.data.engine.media.HOST_MEDIA_STAGE_ROOT_NAME)
                }
            openAdapter(
                NativeEngineOpenRequest
                    .forAppFilesDir(filesDir)
                    .copy(
                        workspace = selection.workspace,
                        mediaStageRoot = stageRoot,
                    ),
            )
        }.onFailure { selection.capabilityToken?.let(capabilityRegistry::revoke) }
            .getOrThrow()

    private fun failPreparation(
        candidate: RustEngineAdapter,
        capabilityToken: String?,
        error: Exception,
    ): Nothing {
        releaseCandidate(candidate, capabilityToken, error, capabilityRegistry)
        throw error
    }

    private data class PreparedSelection(
        val workspace: NativeWorkspaceSelection,
        val capabilityToken: String?,
    )
}
