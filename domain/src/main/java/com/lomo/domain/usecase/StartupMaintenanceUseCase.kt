package com.lomo.domain.usecase

import com.lomo.domain.repository.AppVersionRepository
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.SyncInboxRepository

class StartupMaintenanceUseCase
(
        private val mediaRepository: MediaRepository,
        private val initializeWorkspaceUseCase: InitializeWorkspaceUseCase,
        private val syncAndRebuildUseCase: SyncAndRebuildUseCase,
        private val syncInboxRepository: SyncInboxRepository,
        private val appVersionRepository: AppVersionRepository,
    ) {
        suspend fun initializeRootDirectory(): String? = initializeWorkspaceUseCase.currentRootLocation()?.raw

        suspend fun runDeferredStartupTasks(
            rootDir: String?,
            currentVersion: String,
        ) {
            processSyncInboxOnStartup()
            warmImageCacheOnStartup()
            resyncCachesIfAppVersionChanged(rootDir = rootDir, currentVersion = currentVersion)
        }

        private suspend fun processSyncInboxOnStartup() {
            try {
                syncInboxRepository.processPendingInbox()
            } catch (exception: SyncConflictException) {
                throw exception
            } catch (_: Exception) {
                // Best-effort inbox import.
            }
        }

        private suspend fun warmImageCacheOnStartup() {
            try {
                mediaRepository.refreshImageLocations()
            } catch (_: Exception) {
                // Best-effort cache warm-up.
            }
        }

        private suspend fun resyncCachesIfAppVersionChanged(
            rootDir: String?,
            currentVersion: String,
        ) {
            val lastVersion = appVersionRepository.getLastAppVersionOnce()
            if (lastVersion == currentVersion) return

            if (rootDir != null) {
                try {
                    syncAndRebuildUseCase(forceSync = false)
                } catch (_: Exception) {
                    // Best-effort refresh.
                }

                try {
                    mediaRepository.refreshImageLocations()
                } catch (_: Exception) {
                    // Best-effort cache rebuild.
                }
            }

            appVersionRepository.updateLastAppVersion(currentVersion)
        }
    }
