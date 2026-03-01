package com.lomo.app.feature.main

import com.lomo.app.BuildConfig
import com.lomo.domain.model.StorageArea
import com.lomo.domain.repository.AppVersionRepository
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.usecase.InitializeWorkspaceUseCase
import com.lomo.domain.usecase.SyncAndRebuildUseCase
import com.lomo.domain.repository.AudioPlaybackController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

class MainStartupCoordinator
    @Inject
    constructor(
        private val appConfigRepository: AppConfigRepository,
        private val mediaRepository: MediaRepository,
        private val initializeWorkspaceUseCase: InitializeWorkspaceUseCase,
        private val syncAndRebuildUseCase: SyncAndRebuildUseCase,
        private val appVersionRepository: AppVersionRepository,
        private val audioPlayerController: AudioPlaybackController,
    ) {
        suspend fun initializeRootDirectory(): String? {
            val rootLocation = initializeWorkspaceUseCase.currentRootLocation()
            audioPlayerController.setRootLocation(rootLocation)
            return rootLocation?.raw
        }

        suspend fun runDeferredStartupTasks(rootDir: String?) {
            warmImageCacheOnStartup()
            resyncCachesIfAppVersionChanged(rootDir)
        }

        fun observeRootDirectoryChanges(): Flow<String?> =
            appConfigRepository
                .observeLocation(StorageArea.ROOT)
                .drop(1)
                .onEach { location ->
                    audioPlayerController.setRootLocation(location)
                }
                .map { it?.raw }

        fun observeVoiceDirectoryChanges(): Flow<String?> =
            appConfigRepository
                .observeLocation(StorageArea.VOICE)
                .onEach { voiceLocation ->
                    audioPlayerController.setVoiceLocation(voiceLocation)
                }
                .map { it?.raw }

        private suspend fun warmImageCacheOnStartup() {
            try {
                mediaRepository.refreshImageLocations()
            } catch (_: Exception) {
                // best-effort cache warm-up
            }
        }

        private suspend fun resyncCachesIfAppVersionChanged(rootDir: String?) {
            val currentVersion = "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"
            val lastVersion = appVersionRepository.getLastAppVersionOnce()
            if (lastVersion == currentVersion) return

            if (rootDir != null) {
                try {
                    syncAndRebuildUseCase(forceSync = false)
                } catch (_: Exception) {
                    // best-effort refresh
                }
                try {
                    mediaRepository.refreshImageLocations()
                } catch (_: Exception) {
                    // best-effort cache rebuild
                }
            }

            appVersionRepository.updateLastAppVersion(currentVersion)
        }
    }
