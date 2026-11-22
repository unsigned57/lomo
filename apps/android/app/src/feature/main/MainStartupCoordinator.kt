package com.lomo.app.feature.main

import com.lomo.domain.usecase.GetCurrentAppBuildVersionUseCase
import com.lomo.domain.usecase.ObserveWorkspaceSessionUseCase
import com.lomo.domain.usecase.StartupMaintenanceUseCase

class MainStartupCoordinator(
    private val getCurrentAppBuildVersionUseCase: GetCurrentAppBuildVersionUseCase,
    startupMaintenanceUseCase: () -> StartupMaintenanceUseCase,
    private val observeWorkspaceSessionUseCase: ObserveWorkspaceSessionUseCase,
) {
    // Homepage observation must not construct the sync/archive maintenance graph.
    private val startupMaintenanceUseCase by lazy(startupMaintenanceUseCase)

    suspend fun initializeRootDirectory(): String? {
        observeWorkspaceSessionUseCase.awaitSettledReadiness()
        return observeWorkspaceSessionUseCase.activeWorkspaceLocation.value?.raw
    }

    suspend fun runDeferredStartupTasks(rootDir: String?) {
        startupMaintenanceUseCase.runDeferredStartupTasks(
            rootDir = rootDir,
            currentVersion = getCurrentAppBuildVersionUseCase(),
        )
    }
}
