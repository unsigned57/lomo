package com.lomo.app.feature.main

import com.lomo.app.BuildConfig
import com.lomo.domain.repository.AppVersionRepository
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.MemoRepository
import com.lomo.ui.media.AudioPlayerManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

class MainStartupCoordinator
    @Inject
    constructor(
        private val repository: MemoRepository,
        private val mediaRepository: MediaRepository,
        private val settingsRepository: DirectorySettingsRepository,
        private val appVersionRepository: AppVersionRepository,
        private val audioPlayerManager: AudioPlayerManager,
    ) {
        suspend fun initializeRootDirectory(): String? {
            val rootDirectory = settingsRepository.getRootDirectoryOnce()
            audioPlayerManager.setRootDirectory(rootDirectory)
            return rootDirectory
        }

        suspend fun runDeferredStartupTasks(rootDir: String?) {
            warmImageCacheOnStartup()
            resyncCachesIfAppVersionChanged(rootDir)
        }

        fun observeRootDirectoryChanges(): Flow<String?> =
            settingsRepository
                .getRootDirectory()
                .drop(1)
                .onEach { directory ->
                    audioPlayerManager.setRootDirectory(directory)
                }

        fun observeVoiceDirectoryChanges(): Flow<String?> =
            settingsRepository
                .getVoiceDirectory()
                .onEach { voiceDirectory ->
                    audioPlayerManager.setVoiceDirectory(voiceDirectory)
                }

        private suspend fun warmImageCacheOnStartup() {
            try {
                mediaRepository.syncImageCache()
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
                    repository.refreshMemos()
                } catch (_: Exception) {
                    // best-effort refresh
                }
                try {
                    mediaRepository.syncImageCache()
                } catch (_: Exception) {
                    // best-effort cache rebuild
                }
            }

            appVersionRepository.updateLastAppVersion(currentVersion)
        }
    }
