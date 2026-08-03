package com.lomo.app.feature.main

import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.DerivedIndexRebuildSummary
import com.lomo.domain.model.RecoveryDiagnosticReport
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.WorkspaceMutationLease
import com.lomo.domain.usecase.InitializeWorkspaceUseCase
import com.lomo.domain.usecase.RefreshMemosUseCase
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

class MainWorkspaceCoordinator(
    private val initializeWorkspaceUseCase: InitializeWorkspaceUseCase,
    private val refreshMemosUseCase: RefreshMemosUseCase,
    private val switchRootStorageUseCase: SwitchRootStorageUseCase,
    private val mediaRepository: MediaRepository,
    private val engineReadinessRepository: EngineReadinessRepository,
    private val workspaceMutationLease: WorkspaceMutationLease,
) {
    val engineReadiness: StateFlow<EngineReadiness> = engineReadinessRepository.readiness

    suspend fun createDefaultDirectories(
        forImage: Boolean,
        forVoice: Boolean,
    ) {
        initializeWorkspaceUseCase.ensureDefaultMediaDirectories(forImage, forVoice)
    }

    suspend fun switchRoot(path: String) {
        switchRootStorageUseCase.updateRootLocation(StorageLocation(path))
    }

    suspend fun switchRootAndRefresh(path: String) {
        switchRootStorageUseCase.updateRootLocation(StorageLocation(path))
    }

    suspend fun rebuildCurrentWorkspace() {
        switchRootStorageUseCase.rebuildCurrentWorkspace()
    }

    suspend fun rebuildDerivedIndex(): DerivedIndexRebuildSummary =
        engineReadinessRepository.rebuildDerivedIndex()

    suspend fun createRecoveryDiagnosticReport(): RecoveryDiagnosticReport =
        engineReadinessRepository.createRecoveryDiagnosticReport()

    suspend fun refreshMemos() {
        refreshMemosUseCase()
    }

    suspend fun syncImageCacheBestEffort() {
        try {
            mediaRepository.refreshImageLocations()
        } catch (error: Exception) {
            if (error is CancellationException) {
                throw error
            }
            // Best-effort background sync.
        }
    }

    /**
     * Recovery retry is the same transition as a settings switch and a cold restore.
     *
     * Activating the engine directly here would skip candidate probing, the mutation barrier and
     * the rebuild/rollback policy, which is how a third activation workflow drifted from the other
     * two.
     */
    suspend fun retryEngineOpen(rootPath: String) {
        switchRootStorageUseCase.updateRootLocation(StorageLocation(rootPath))
    }

    fun resnapshotEngine() {
        engineReadinessRepository.resnapshot()
    }

    /**
     * Observe-root rebuild is suppressed while a workspace transition is draining writers and until
     * the active engine identity matches the persisted selection at Ready. SwitchRoot is the sole
     * rebuild owner for intentional root switches (settings + main picker).
     */
    fun canObserveRootRebuild(directory: String): Boolean {
        if (!workspaceMutationLease.isWritable()) return false
        return engineReadinessRepository.activeWorkspaceLocation.value?.raw == directory
    }
}
