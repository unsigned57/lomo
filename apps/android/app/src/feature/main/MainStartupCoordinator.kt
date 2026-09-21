package com.lomo.app.feature.main

import com.lomo.app.feature.common.AppConfigStateProvider
import com.lomo.app.media.AudioPlayerManager
import com.lomo.domain.usecase.GetCurrentAppBuildVersionUseCase
import com.lomo.domain.usecase.ObserveWorkspaceSessionUseCase
import com.lomo.domain.usecase.StartupMaintenanceUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach


class MainStartupCoordinator(
    private val getCurrentAppBuildVersionUseCase: GetCurrentAppBuildVersionUseCase,
    private val startupMaintenanceUseCase: StartupMaintenanceUseCase,
    private val appConfigStateProvider: AppConfigStateProvider,
    private val audioPlayerManager: AudioPlayerManager,
    private val observeWorkspaceSessionUseCase: ObserveWorkspaceSessionUseCase,
) {
        suspend fun initializeRootDirectory(): String? {
            observeWorkspaceSessionUseCase.awaitSettledReadiness()
            return observeWorkspaceSessionUseCase.activeWorkspaceLocation.value
                ?.raw
                .also(audioPlayerManager.setRootLocation)
        }

        suspend fun runDeferredStartupTasks(rootDir: String?) {
            startupMaintenanceUseCase.runDeferredStartupTasks(
                rootDir = rootDir,
                currentVersion = getCurrentAppBuildVersionUseCase(),
            )
        }

        fun observeRootDirectoryChanges(): Flow<String?> =
            observeWorkspaceSessionUseCase.observeActiveRootPath()
                .onEach(audioPlayerManager.setRootLocation)

        fun observeVoiceDirectoryChanges(): Flow<String?> =
            appConfigStateProvider.voiceDirectory
                .onEach(audioPlayerManager.setVoiceLocation)
    }
