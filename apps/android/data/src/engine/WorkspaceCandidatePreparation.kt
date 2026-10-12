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
 * Reads the configured storage filename date token at candidate-open time so a format change
 * applies to the next session without caching a stale snapshot. The token is transported
 * verbatim; Rust `DateFormat` is the only resolver.
 */
internal fun interface SessionDateFormatSource {
    suspend fun token(): String
}

/**
 * Inputs the session forwards verbatim into every workspace-candidate open. The capability
 * registry stays session-owned so grant registration and token revoke share one instance.
 */
internal data class CandidateOpenContext(
    val filesDir: File,
    val openAdapter: (NativeEngineOpenRequest) -> RustEngineAdapter,
    val isContentUri: (String) -> Boolean,
    val sessionDateFormat: SessionDateFormatSource,
)

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
    private val sessionDateFormat: SessionDateFormatSource,
) {
    fun openBootstrap(): ManagedWorkspaceCandidate =
        ManagedWorkspaceCandidate.bootstrap(
            openAdapter(NativeEngineOpenRequest.forAppFilesDir(filesDir)),
        )

    suspend fun prepare(location: StorageLocation): ManagedWorkspaceCandidate {
        val selection = selectionFor(location)
        val candidate = openWorkspaceAdapter(selection)
        val candidateReadiness = candidate.readiness.value
        if (candidateReadiness is EngineReadiness.Ready) {
            return try {
                // The open receipt is the only projection authority: FFI open returns only
                // after the initial rebuild commits, and no store read can race the receipt.
                val projectionRevision =
                    requireNotNull(candidate.sessionProjectionRevision) {
                        "Workspace session open completed without a projection revision"
                    }
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

    suspend fun rebuildProjectionForRecovery(
        location: StorageLocation,
        batchSize: UInt,
    ): com.lomo.nativebridge.StoreRebuildResult {
        val selection = selectionFor(location)
        val repairAdapter = openWorkspaceAdapter(selection)
        var result: com.lomo.nativebridge.StoreRebuildResult? = null
        var failure: Throwable? = null
        try {
            result = repairAdapter.startRebuild(batchSize)
        } catch (cancelled: CancellationException) {
            failure = cancelled
            throw cancelled
        } catch (thrown: Exception) {
            failure = thrown
        } finally {
            val closeFailure = repairAdapter.closeResult()
            // Capability revoke is never skipped by a failing adapter close.
            selection.capabilityToken?.let(capabilityRegistry::revoke)
            closeFailure?.let { failure?.addSuppressed(it) }
            if (failure == null) {
                failure = closeFailure
            }
        }
        return if (failure == null) {
            checkNotNull(result)
        } else {
            throw failure
        }
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

    private suspend fun openWorkspaceAdapter(selection: PreparedSelection): RustEngineAdapter {
        // Stage root follows the candidate selection, not the currently active workspace: a
        // Direct workspace stages beside its root while SAF workspaces share the app-private
        // host stage root.
        val stageRoot =
            when (val workspace = selection.workspace) {
                is NativeWorkspaceSelection.Direct -> workspace.rootPath
                is NativeWorkspaceSelection.Saf ->
                    File(filesDir, com.lomo.data.engine.media.HOST_MEDIA_STAGE_ROOT_NAME)
            }
        return try {
            openAdapter(
                NativeEngineOpenRequest
                    .forAppFilesDir(filesDir)
                    .copy(
                        workspace = selection.workspace,
                        mediaStageRoot = stageRoot,
                        dateFormat = sessionDateFormat.token(),
                    ),
            )
        } catch (failure: Exception) {
            selection.capabilityToken?.let(capabilityRegistry::revoke)
            throw failure
        }
    }

    private fun RustEngineAdapter.closeResult(): Throwable? = runCatching { close() }.exceptionOrNull()

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
