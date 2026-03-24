package com.lomo.data.repository

import androidx.core.net.toUri
import com.lomo.data.source.MediaStorageDataSource
import com.lomo.data.source.StorageRootType
import com.lomo.data.source.WorkspaceConfigSource
import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.MediaCategory
import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.MediaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import timber.log.Timber
import javax.inject.Inject

class MediaRepositoryImpl
    @Inject
    constructor(
        private val workspaceConfigSource: WorkspaceConfigSource,
        private val mediaStorageDataSource: MediaStorageDataSource,
    ) : MediaRepository {
        private val imageLocationMap = MutableStateFlow<Map<MediaEntryId, StorageLocation>>(emptyMap())

        override suspend fun importImage(source: StorageLocation): StorageLocation {
            val filename = mediaStorageDataSource.saveImage(source.raw.toUri())
            mediaStorageDataSource.getImageLocation(filename)?.let { location ->
                imageLocationMap.update { currentMap ->
                    currentMap + (MediaEntryId(filename) to StorageLocation(location))
                }
            }
            return StorageLocation(filename)
        }

        override suspend fun removeImage(entryId: MediaEntryId) {
            mediaStorageDataSource.deleteImage(entryId.raw)
            imageLocationMap.update { currentMap -> currentMap - entryId }
        }

        override fun observeImageLocations(): Flow<Map<MediaEntryId, StorageLocation>> = imageLocationMap.asStateFlow()

        override suspend fun refreshImageLocations() {
            if (workspaceConfigSource.getRootFlow(StorageRootType.IMAGE).first() == null) {
                imageLocationMap.value = emptyMap()
                return
            }

            imageLocationMap.value =
                mediaStorageDataSource.listImageFiles().associate { (name, uri) ->
                    MediaEntryId(name) to StorageLocation(uri)
                }
        }

        override suspend fun ensureCategoryWorkspace(category: MediaCategory): StorageLocation? =
            when (category) {
                MediaCategory.IMAGE -> {
                    createDefaultWorkspace(
                        folderName = IMAGE_DIRECTORY_NAME,
                        setRoot = { uri -> workspaceConfigSource.setRoot(StorageRootType.IMAGE, uri) },
                    )
                }

                MediaCategory.VOICE -> {
                    createDefaultWorkspace(
                        folderName = VOICE_DIRECTORY_NAME,
                        setRoot = { uri -> workspaceConfigSource.setRoot(StorageRootType.VOICE, uri) },
                    )
                }
            }

        override suspend fun allocateVoiceCaptureTarget(entryId: MediaEntryId): StorageLocation =
            StorageLocation(mediaStorageDataSource.createVoiceFile(entryId.raw).toString())

        override suspend fun removeVoiceCapture(entryId: MediaEntryId) {
            mediaStorageDataSource.deleteVoiceFile(entryId.raw)
        }

        private suspend fun createDefaultWorkspace(
            folderName: String,
            setRoot: suspend (String) -> Unit,
        ): StorageLocation? =
            runNonFatalCatching {
                val uri = workspaceConfigSource.createDirectory(folderName)
                setRoot(uri)
                StorageLocation(uri)
            }.getOrElse { error ->
                Timber.tag(TAG).w(
                    error,
                    "Failed to create default media workspace: folder=%s",
                    folderName,
                )
                null
            }

        companion object {
            private const val TAG = "MediaRepositoryImpl"
            private const val IMAGE_DIRECTORY_NAME = "images"
            private const val VOICE_DIRECTORY_NAME = "voice"
        }
    }
