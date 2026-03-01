package com.lomo.app.feature.main

import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.usecase.InitializeWorkspaceUseCase
import com.lomo.domain.usecase.RefreshMemosUseCase
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

class MainWorkspaceCoordinator
    @Inject
    constructor(
        private val repository: MemoRepository,
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

        suspend fun switchRootAndRefresh(path: String) {
            switchRootStorageUseCase.updateRootLocation(StorageLocation(path))
            repository.refreshMemos()
        }

        suspend fun refreshMemos() {
            refreshMemosUseCase()
        }

        suspend fun syncImageCacheBestEffort() {
            try {
                mediaRepository.refreshImageLocations()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best-effort background sync.
            }
        }
    }
