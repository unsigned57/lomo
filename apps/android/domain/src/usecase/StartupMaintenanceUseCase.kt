package com.lomo.domain.usecase

import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.UnifiedSyncOperation
import com.lomo.domain.repository.AppVersionRepository
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.SyncInboxRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class StartupMaintenanceUseCase
(
        private val mediaRepository: MediaRepository,
        private val initializeWorkspaceUseCase: InitializeWorkspaceUseCase,
        private val syncAndRebuildUseCase: SyncAndRebuildUseCase,
        private val syncProviderRegistry: SyncProviderRegistry,
        private val appVersionRepository: AppVersionRepository,
        private val syncInboxRepository: SyncInboxRepository,
    ) {
        suspend fun initializeRootDirectory(): String? = initializeWorkspaceUseCase.currentRootLocation()?.raw

        suspend fun runDeferredStartupTasks(
            rootDir: String?,
            currentVersion: String,
        ) {
            coroutineScope {
                val imageWarmup =
                    launch {
                        warmImageCacheOnStartup()
                    }
                val inboxProcessing =
                    launch {
                        processSyncInboxOnStartup()
                    }
                imageWarmup.join()
                inboxProcessing.join()
            }
            resyncCachesIfAppVersionChanged(rootDir = rootDir, currentVersion = currentVersion)
        }

        private suspend fun warmImageCacheOnStartup() {
            try {
                mediaRepository.refreshImageLocations()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // behavior-contract: silent-result-ok: image-map warmup is optional; missing Direct root is ignored
            }
            // D6: deterministic orphan reclaim at maintenance boundary (media-trash / expiry).
            try {
                mediaRepository.runOrphanSweepAtOperationBoundary()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // behavior-contract: silent-result-ok: orphan sweep is optional; missing Direct root is a no-op
            }
        }

        private suspend fun processSyncInboxOnStartup() {
            try {
                syncInboxRepository.ensureDirectoryStructure()
                syncProviderRegistry
                    .get(SyncBackendType.INBOX)
                    ?.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES)
                    ?.toSyncFailureOrNull()
                    ?.let { throw it }
            } catch (error: Exception) {
                if (error is CancellationException || error is SyncConflictException) throw error
                // behavior-contract: silent-result-ok: inbox import is optional; a missing inbox must not block startup
            }
        }

        private suspend fun resyncCachesIfAppVersionChanged(
            rootDir: String?,
            currentVersion: String,
        ) {
            val lastVersion = appVersionRepository.getLastAppVersionOnce()
            if (lastVersion == currentVersion) return

            if (rootDir != null) {
                syncAndRebuildUseCase(forceSync = false)

                try {
                    mediaRepository.refreshImageLocations()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    // behavior-contract: silent-result-ok: image-map rebuild is optional;
                    // a missing Direct root is ignored
                }
            }

            appVersionRepository.updateLastAppVersion(currentVersion)
        }
    }
