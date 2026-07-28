package com.lomo.domain.usecase

import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.WorkspaceCandidateValidator
import com.lomo.domain.repository.WorkspaceMutationLease
import com.lomo.domain.repository.WorkspaceStateResolver

/**
 * Switches the workspace root with prepare → validate → persist → activate → rebuild ordering.
 *
 * Candidate validation runs before any durable selection change. The whole critical section runs
 * under an exclusive mutation transition: new writers are refused and every writer already admitted
 * is drained before the workspace changes, so no mutation can straddle the switch. The engine is
 * activated under the transition after selection persistence so only one engine is authoritative.
 * Soft Recovery and hard open failure both restore the previous selection, previous engine, and
 * previous index (mandatory rebuild after any abort that may have cleared projections) when a
 * previous selection existed; the transition always ends. SwitchRoot is the sole rebuild owner for
 * a root switch — observe-root must not rebuild while a transition is active.
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
        workspaceMutationLease.withExclusiveTransition {
            directorySettingsRepository.applyRootLocation(location)
            val activated =
                runCatching {
                    engineReadinessRepository.activateWorkspace(location)
                    rebuildCurrentWorkspace()
                }
            if (activated.isFailure) {
                val originalFailure = checkNotNull(activated.exceptionOrNull())
                try {
                    restorePreviousAuthority(previousSelection)
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
        // Restore previous selection + engine + index. Rebuild is mandatory: candidate rebuild may
        // have already cleared Room before failing, leaving Ready engine with an empty index.
        try {
            directorySettingsRepository.applyRootLocation(previousSelection)
            engineReadinessRepository.activateWorkspace(previousSelection)
            rebuildCurrentWorkspace()
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
 * The original activate/rebuild failure is attached as a suppressed exception by the caller so
 * diagnostics keep both facts and UI can surface Recovery instead of a silent half-switch.
 */
class WorkspaceAuthorityRestoreException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
