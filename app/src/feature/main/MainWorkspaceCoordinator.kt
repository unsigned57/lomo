package com.lomo.app.feature.main

import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.usecase.InitializeWorkspaceUseCase
import com.lomo.domain.usecase.RefreshMemosUseCase
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import kotlinx.coroutines.CancellationException


class MainWorkspaceCoordinator(
    private val initializeWorkspaceUseCase: InitializeWorkspaceUseCase,
    private val refreshMemosUseCase: RefreshMemosUseCase,
    private val switchRootStorageUseCase: SwitchRootStorageUseCase,
    private val mediaRepository: MediaRepository,
) {
        suspend fun createDefaultDirectories(
            forImage: Boolean,
            forVoice: Boolean,
        ) {
            initializeWorkspaceUseCase.ensureDefaultMediaDirectories(forImage, forVoice)
        }

        suspend fun switchRoot(path: String) {
            switchRootStorageUseCase.updateRootLocation(StorageLocation(path))
        }

        suspend fun switchRootAndRefresh(path: String) {
            switchRootStorageUseCase.updateRootLocation(StorageLocation(path))
        }

        suspend fun rebuildCurrentWorkspace() {
            switchRootStorageUseCase.rebuildCurrentWorkspace()
        }

        suspend fun refreshMemos() {
            refreshMemosUseCase()
        }

        suspend fun syncImageCacheBestEffort() {
            try {
                mediaRepository.refreshImageLocations()
            } catch (error: Exception) {
                if (error is CancellationException) {
                    throw error
                }
                // Best-effort background sync.
            }
        }
    }
