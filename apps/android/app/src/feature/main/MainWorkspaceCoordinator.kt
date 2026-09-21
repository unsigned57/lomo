package com.lomo.app.feature.main

import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.DerivedIndexRebuildSummary
import com.lomo.domain.model.RecoveryDiagnosticReport
import com.lomo.domain.model.ProjectionFreshness
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.WorkspaceAuthority
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.usecase.InitializeWorkspaceUseCase
import com.lomo.domain.usecase.RefreshMemosUseCase
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import kotlinx.coroutines.flow.StateFlow

class MainWorkspaceCoordinator(
    private val initializeWorkspaceUseCase: InitializeWorkspaceUseCase,
    private val refreshMemosUseCase: RefreshMemosUseCase,
    private val switchRootStorageUseCase: SwitchRootStorageUseCase,
    private val mediaRepository: MediaRepository,
    private val engineReadinessRepository: EngineReadinessRepository,
) {
    val engineReadiness: StateFlow<EngineReadiness> = engineReadinessRepository.readiness
    val activeWorkspaceLocation: StateFlow<StorageLocation?> =
        engineReadinessRepository.activeWorkspaceLocation
    val workspaceAuthority: StateFlow<WorkspaceAuthority?> = engineReadinessRepository.workspaceAuthority
    val projectionFreshness: StateFlow<ProjectionFreshness> = engineReadinessRepository.projectionFreshness
    val mount = engineReadinessRepository.mount

    suspend fun createDefaultDirectories(
        forImage: Boolean,
        forVoice: Boolean,
    ) {
        initializeWorkspaceUseCase.ensureDefaultMediaDirectories(forImage, forVoice)
    }

    suspend fun switchRootAndRefresh(path: String) {
        switchRootStorageUseCase.updateRootLocation(StorageLocation(path))
    }

    suspend fun rebuildDerivedIndex(): DerivedIndexRebuildSummary =
        engineReadinessRepository.rebuildDerivedIndex()

    suspend fun createRecoveryDiagnosticReport(): RecoveryDiagnosticReport =
        engineReadinessRepository.createRecoveryDiagnosticReport()

    suspend fun refreshMemos() {
        refreshMemosUseCase()
    }

    suspend fun syncImageCache() {
        mediaRepository.refreshImageLocations()
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
}
