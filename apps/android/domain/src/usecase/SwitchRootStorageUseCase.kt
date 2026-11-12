package com.lomo.domain.usecase

import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.MediaDirectoryChange
import com.lomo.domain.model.ProjectionFreshness
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.WorkspaceAuthority
import com.lomo.domain.model.WorkspaceRootTransition
import com.lomo.domain.model.WorkspaceRootTransitionPhase
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.WorkspaceCandidateValidator
import com.lomo.domain.repository.WorkspaceMutationLease
import com.lomo.domain.repository.WorkspaceStateResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Switches the workspace root with validate → durable prepare → activate → durable commit ordering.
 *
 * Candidate validation runs before any durable selection change. The whole critical section runs
 * under an exclusive mutation transition: new writers are refused and every writer already admitted
 * is drained before the workspace changes, so no mutation can straddle the switch. The engine is
 * activated while the committed selection remains unchanged. Activation opens and promotes the
 * candidate authority; only after it succeeds is the candidate marked activated and atomically
 * published as the committed root. A still-verified previous mount publishes Revalidating during
 * prepare; install then publishes Verified. The mutation lease rejects writes until that verified
 * projection exists. Same-location retry after Recovery reactivates a new generation without opening
 * another root-transition journal. A crash before commit therefore restores the previous root. Soft
 * Recovery and hard open failure restore previous engine authority and roll back the durable journal.
 *
 * The transition is a durable journal state machine, so it must reach a terminal phase (commit or
 * rollback) even when the calling coroutine is cancelled — a caller abandoning the wait (e.g. a
 * screen navigating away) must never leave a prepared journal half-finished. The body therefore
 * runs under [NonCancellable]; blocking engine/SAF work is still dispatched off the caller thread
 * by the session layer.
 */
open class SwitchRootStorageUseCase(
    private val directorySettingsRepository: DirectorySettingsRepository,
    private val workspaceStateResolver: WorkspaceStateResolver,
    private val workspaceMutationLease: WorkspaceMutationLease,
    private val engineReadinessRepository: EngineReadinessRepository,
    private val workspaceCandidateValidator: WorkspaceCandidateValidator =
        WorkspaceCandidateValidator { location ->
            require(location.raw.isNotBlank()) { "Candidate workspace location must be non-blank" }
        },
) {
    private val selectionMutex = Mutex()

    /** Serialize validation, selection reads and commit/rollback for all three storage areas. */
    private suspend fun completeWorkspaceTransition(action: suspend () -> Unit) {
        val outcome = withContext(NonCancellable) {
            // Cold recovery owns journal cleanup. It must settle before a user command can
            // prepare a new journal, otherwise startup could discard that live transaction.
            engineReadinessRepository.requestEngineStart()
            selectionMutex.withLock { captureWorkspaceTransition { action() } }
        }
        when (outcome) {
            WorkspaceTransitionOutcome.Committed -> Unit
            is WorkspaceTransitionOutcome.Failed -> throw outcome.cause
        }
    }

    /** Rebinds an attachment grant after draining writes and retiring the workspace's sole owner. */
    open suspend fun updateMediaLocation(update: StorageAreaUpdate) {
        require(update.area == StorageArea.IMAGE || update.area == StorageArea.VOICE)
        val location = requireNotNull(update.location) { "A selected media directory is required" }
        completeWorkspaceTransition {
            workspaceCandidateValidator.validate(location)
            workspaceMutationLease.withExclusiveTransition {
                val workspace = engineReadinessRepository.activeWorkspaceLocation.value
                if (workspace == null) {
                    directorySettingsRepository.applyLocation(update)
                    return@withExclusiveTransition
                }
                val previous = directorySettingsRepository.currentLocation(update.area)
                if (previous == location) {
                    if (engineReadinessRepository.readiness.value !is EngineReadiness.Ready) {
                        engineReadinessRepository.activateWorkspace(workspace, mediaChange = null)
                    }
                    return@withExclusiveTransition
                }
                val transition = resolveSwitchTransition(
                    workspace,
                    MediaDirectoryChange(update.area, previous, location),
                )
                activateAndCommit(transition, previousWorkspace = workspace)
            }
        }
    }

    open suspend fun updateRootLocation(location: StorageLocation) {
        completeWorkspaceTransition { updateRootLocationLocked(location) }
    }

    private suspend fun updateRootLocationLocked(location: StorageLocation) {
        // Prepare + validate before mutating durable selection.
        workspaceCandidateValidator.validate(location)
        val previousSelection = directorySettingsRepository.currentRootLocation()
        if (previousSelection == location) {
            if (engineReadinessRepository.readiness.value is EngineReadiness.Ready) {
                return
            }
            workspaceMutationLease.withExclusiveTransition {
                engineReadinessRepository.activateWorkspace(location, mediaChange = null)
            }
            return
        }
        workspaceMutationLease.withExclusiveTransition {
            val transition = resolveSwitchTransition(location, mediaChange = null)
            activateAndCommit(transition, previousWorkspace = previousSelection)
        }
    }

    /** The same prepare/index/commit and rollback protocol serves every storage directory. */
    private suspend fun activateAndCommit(transition: WorkspaceRootTransition, previousWorkspace: StorageLocation?) {
        val previousAuthority = engineReadinessRepository.mount.value.authority
        val outcome = captureWorkspaceTransition {
            // Same-workspace attachment rebinding must retire the native sole owner before
            // opening its candidate. No directory preference has changed at this point.
            if (transition.mediaChange != null) engineReadinessRepository.clearWorkspace()
            engineReadinessRepository.activateWorkspace(transition.candidate, transition.mediaChange)
            if (transition.phase == WorkspaceRootTransitionPhase.PREPARED) {
                directorySettingsRepository.markRootTransitionActivated(transition.id)
            }
            directorySettingsRepository.commitRootTransition(transition.id)
        }
        when (outcome) {
            WorkspaceTransitionOutcome.Committed -> Unit

            is WorkspaceTransitionOutcome.Failed -> throw resolveSwitchFailure(
                transitionId = transition.id,
                previousSelection = previousWorkspace,
                previousAuthority = previousAuthority,
                originalFailure = outcome.cause,
            )
        }
    }

    /**
     * Picks the durable transition for this switch. A pending transition aimed at the same
     * candidate is the original operation: it resumes instead of being recreated, so an
     * interrupted switch commits under its own operation id. A pending transition aimed
     * elsewhere is a stale operation and is rolled back before a fresh prepare.
     */
    private suspend fun resolveSwitchTransition(
        location: StorageLocation,
        mediaChange: MediaDirectoryChange?,
    ): WorkspaceRootTransition {
        val pending = directorySettingsRepository.pendingRootTransition() ?: run {
            return directorySettingsRepository.prepareRootTransition(location, mediaChange)
        }
        if (pending.candidate == location && pending.mediaChange == mediaChange) {
            return pending
        }
        directorySettingsRepository.rollbackRootTransition(pending.id)
        return directorySettingsRepository.prepareRootTransition(location, mediaChange)
    }

    open suspend fun rebuildCurrentWorkspace() {
        workspaceStateResolver.rebuildFromCurrentWorkspace()
    }

    private suspend fun resolveSwitchFailure(
        transitionId: String,
        previousSelection: StorageLocation?,
        originalFailure: Exception,
        previousAuthority: WorkspaceAuthority?,
    ): Throwable {
        val rollbackFailure =
            try {
                directorySettingsRepository.rollbackRootTransition(transitionId)
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                error
            }
        val restoreFailure =
            try {
                restorePreviousAuthority(previousSelection, previousAuthority)
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                error
            }
        return when {
            restoreFailure != null ->
                restoreFailure.toAuthorityRestoreFailure(
                    message = "Failed to restore previous workspace authority after switch failure",
                    originalFailure = originalFailure,
                    secondaryFailure = rollbackFailure,
                )

            rollbackFailure != null ->
                rollbackFailure.toAuthorityRestoreFailure(
                    message = "Failed to rollback root transition journal after switch failure",
                    originalFailure = originalFailure,
                )

            else -> originalFailure
        }
    }

    private suspend fun restorePreviousAuthority(
        previousSelection: StorageLocation?,
        previousAuthority: WorkspaceAuthority?,
    ) {
        val retained = engineReadinessRepository.mount.value
        if (retained.location == previousSelection && retained.authority == previousAuthority &&
            retained.admitsProjectionReads &&
            retained.freshness is ProjectionFreshness.Verified
        ) {
            return
        }
        if (previousSelection == null) {
            // No prior selection: clear the candidate engine so Awaiting remains authoritative.
            wrapAuthorityRestore("Failed to clear candidate workspace after switch failure") {
                engineReadinessRepository.clearWorkspace()
            }
            return
        }
        wrapAuthorityRestore("Failed to restore previous workspace authority after switch failure") {
            engineReadinessRepository.activateWorkspace(previousSelection, mediaChange = null)
        }
    }

    private suspend fun wrapAuthorityRestore(message: String, action: suspend () -> Unit) {
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw WorkspaceAuthorityRestoreException(
                message = "$message: ${error.message ?: error.javaClass.simpleName}",
                cause = error,
            )
        }
    }
}

/** Terminal outcomes of an atomic workspace transition; fatal JVM errors are not domain failures. */
private sealed interface WorkspaceTransitionOutcome {
    data object Committed : WorkspaceTransitionOutcome
    data class Failed(val cause: Exception) : WorkspaceTransitionOutcome
}

/**
 * Both callers run under the NonCancellable transition owner. Inlining retains that context;
 * a dependency cancellation is a failed transition which must finish rollback before escaping.
 * Every returned outcome is consumed: no exception is discarded or converted to success.
 */
private inline fun captureWorkspaceTransition(action: () -> Unit): WorkspaceTransitionOutcome = try {
    action()
    WorkspaceTransitionOutcome.Committed
} catch (failure: Exception) {
    WorkspaceTransitionOutcome.Failed(failure)
}

private fun Throwable.toAuthorityRestoreFailure(
    message: String,
    originalFailure: Exception,
    secondaryFailure: Throwable? = null,
): WorkspaceAuthorityRestoreException {
    val structured =
        this as? WorkspaceAuthorityRestoreException
            ?: WorkspaceAuthorityRestoreException(
                message = "$message: ${this.message ?: javaClass.simpleName}",
                cause = this,
            )
    secondaryFailure?.let(structured::addSuppressed)
    structured.addSuppressed(originalFailure)
    return structured
}

/**
 * Structured failure when switch abort cannot re-establish previous workspace authority.
 *
 * The original activation failure is attached as a suppressed exception by the caller so
 * diagnostics keep both facts and UI can surface Recovery instead of a silent half-switch.
 */
class WorkspaceAuthorityRestoreException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
