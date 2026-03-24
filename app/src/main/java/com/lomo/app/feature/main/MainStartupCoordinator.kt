package com.lomo.app.feature.main

import com.lomo.app.BuildConfig
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.media.AudioPlayerManager
import com.lomo.domain.usecase.StartupMaintenanceUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

class MainStartupCoordinator
    @Inject
    constructor(
        private val startupMaintenanceUseCase: StartupMaintenanceUseCase,
        private val appConfigUiCoordinator: AppConfigUiCoordinator,
        private val audioPlayerManager: AudioPlayerManager,
    ) {
        suspend fun initializeRootDirectory(): String? =
            startupMaintenanceUseCase
                .initializeRootDirectory()
                .also(audioPlayerManager.setRootLocation)

        suspend fun runDeferredStartupTasks(rootDir: String?) {
            startupMaintenanceUseCase.runDeferredStartupTasks(
                rootDir = rootDir,
                currentVersion = "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})",
            )
        }

        fun observeRootDirectoryChanges(): Flow<String?> =
            appConfigUiCoordinator
                .rootDirectory()
                .drop(1)
                .onEach(audioPlayerManager.setRootLocation)

        fun observeVoiceDirectoryChanges(): Flow<String?> =
            appConfigUiCoordinator
                .voiceDirectory()
                .onEach(audioPlayerManager.setVoiceLocation)
    }
