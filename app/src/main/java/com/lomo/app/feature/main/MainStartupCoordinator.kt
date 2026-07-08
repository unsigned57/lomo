package com.lomo.app.feature.main

import com.lomo.app.BuildConfig
import com.lomo.app.feature.common.AppConfigStateProvider
import com.lomo.app.media.AudioPlayerManager
import com.lomo.domain.usecase.StartupMaintenanceUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.onEach


class MainStartupCoordinator(
    private val startupMaintenanceUseCase: StartupMaintenanceUseCase,
    private val appConfigStateProvider: AppConfigStateProvider,
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
            appConfigStateProvider.rootDirectory
                .drop(1)
                .onEach(audioPlayerManager.setRootLocation)

        fun observeVoiceDirectoryChanges(): Flow<String?> =
            appConfigStateProvider.voiceDirectory
                .onEach(audioPlayerManager.setVoiceLocation)
    }
