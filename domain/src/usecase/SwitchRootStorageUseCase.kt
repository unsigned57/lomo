package com.lomo.domain.usecase

import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.WorkspaceCandidateValidator
import com.lomo.domain.repository.WorkspaceMutationLease
import com.lomo.domain.repository.WorkspaceStateResolver

/**
 * Switches the workspace root with validate → durable prepare → activate → durable commit ordering.
 *
 * Candidate validation runs before any durable selection change. The whole critical section runs
 * under an exclusive mutation transition: new writers are refused and every writer already admitted
 * is drained before the workspace changes, so no mutation can straddle the switch. The engine is
 * activated while the committed selection remains unchanged. Activation owns candidate projection
 * rebuild and promotion as one transaction; only after it succeeds is the candidate marked activated
 * and atomically published as the committed root. A crash before commit therefore restores the
 * previous root. Soft Recovery and hard open failure restore previous engine authority and roll back
 * the durable journal; this use case never starts a second rebuild.
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
    open suspend fun updateRootLocation(location: StorageLocation) {
        // Prepare + validate before mutating durable selection.
        workspaceCandidateValidator.validate(location)
        val previousSelection = directorySettingsRepository.currentRootLocation()
        if (previousSelection == location) return
        workspaceMutationLease.withExclusiveTransition {
            val transition = directorySettingsRepository.prepareRootTransition(location)
            try {
                engineReadinessRepository.activateWorkspace(location)
                directorySettingsRepository.markRootTransitionActivated(transition.id)
                directorySettingsRepository.commitRootTransition(transition.id)
            } catch (originalFailure: Exception) {
                try {
                    restorePreviousAuthority(previousSelection)
                    directorySettingsRepository.rollbackRootTransition(transition.id)
                } catch (restoreFailure: Exception) {
                    val structured =
                        restoreFailure as? WorkspaceAuthorityRestoreException
                            ?: WorkspaceAuthorityRestoreException(
                                message =
                                    "Failed to restore previous workspace authority after switch failure: " +
                                        (restoreFailure.message ?: restoreFailure.javaClass.simpleName),
                                cause = restoreFailure,
                            )
                    structured.addSuppressed(originalFailure)
                    throw structured
                }
                throw originalFailure
            }
        }
    }

    open suspend fun rebuildCurrentWorkspace() {
        workspaceStateResolver.rebuildFromCurrentWorkspace()
    }

    private suspend fun restorePreviousAuthority(previousSelection: StorageLocation?) {
        if (previousSelection == null) {
            // No prior selection: clear the candidate engine so Awaiting remains authoritative.
            // Clear failure is itself a recovery-worthy authority loss and must surface.
            try {
                engineReadinessRepository.clearWorkspace()
            } catch (clearFailure: Exception) {
                throw WorkspaceAuthorityRestoreException(
                    message =
                        "Failed to clear candidate workspace after switch failure: " +
                            (clearFailure.message ?: clearFailure.javaClass.simpleName),
                    cause = clearFailure,
                )
            }
            return
        }
        // Reopening the previous engine goes through the same session-owned projection transaction.
        try {
            engineReadinessRepository.activateWorkspace(previousSelection)
        } catch (restoreFailure: Exception) {
            throw WorkspaceAuthorityRestoreException(
                message =
                    "Failed to restore previous workspace authority after switch failure: " +
                        (restoreFailure.message ?: restoreFailure.javaClass.simpleName),
                cause = restoreFailure,
            )
        }
    }
}

/**
 * Structured failure when switch abort cannot re-establish previous workspace authority.
 *
 * The original activation failure is attached as a suppressed exception by the caller so
 * diagnostics keep both facts and UI can surface Recovery instead of a silent half-switch.
 */
class WorkspaceAuthorityRestoreException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
