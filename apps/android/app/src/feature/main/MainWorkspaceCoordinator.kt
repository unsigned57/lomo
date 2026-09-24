package com.lomo.app.feature.main

import com.lomo.domain.model.DerivedIndexRebuildSummary
import com.lomo.domain.model.RecoveryDiagnosticReport
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.usecase.InitializeWorkspaceUseCase
import com.lomo.domain.usecase.RefreshMemosUseCase
import com.lomo.domain.usecase.SwitchRootStorageUseCase

class MainWorkspaceCoordinator(
    private val initializeWorkspaceUseCase: InitializeWorkspaceUseCase,
    private val refreshMemosUseCase: RefreshMemosUseCase,
    private val switchRootStorageUseCase: SwitchRootStorageUseCase,
    private val mediaRepository: MediaRepository,
    private val engineReadinessRepository: EngineReadinessRepository,
) {
    /**
     * The single published mount fact for the active engine session. Every UI/data consumer derives
     * read admission here instead of recombining readiness, location, authority and freshness.
     */
    val mount = engineReadinessRepository.mount

    /**
     * Explicit engine start for hosts that need the workspace. The Application never opens native
     * on behalf of ambient wakes (tile state reads, staged recording); an Activity entry requests
     * the engine here and the result settles through [mount].
     */
    suspend fun requestEngineStart(): com.lomo.domain.model.EngineReadiness =
        engineReadinessRepository.requestEngineStart()

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
