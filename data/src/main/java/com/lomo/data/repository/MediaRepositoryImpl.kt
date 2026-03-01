package com.lomo.data.repository

import android.net.Uri
import com.lomo.data.source.FileDataSource
import com.lomo.domain.model.MediaCategory
import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.MediaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject

class MediaRepositoryImpl
    @Inject
    constructor(
        private val dataSource: FileDataSource,
    ) : MediaRepository {
        companion object {
            private const val TAG = "MediaRepositoryImpl"
            private const val IMAGE_DIRECTORY_NAME = "images"
            private const val VOICE_DIRECTORY_NAME = "voice"
        }

        private val imageLocationMap = MutableStateFlow<Map<MediaEntryId, StorageLocation>>(emptyMap())

        override suspend fun importImage(source: StorageLocation): StorageLocation =
            StorageLocation(dataSource.saveImage(Uri.parse(source.raw)))

        override suspend fun removeImage(entryId: MediaEntryId) {
            dataSource.deleteImage(entryId.raw)
        }

        override fun observeImageLocations(): Flow<Map<MediaEntryId, StorageLocation>> = imageLocationMap.asStateFlow()

        override suspend fun refreshImageLocations() {
            if (dataSource.getImageRootFlow().first() == null) {
                imageLocationMap.value = emptyMap()
                return
            }

            imageLocationMap.value =
                dataSource.listImageFiles().associate { (name, uri) ->
                    MediaEntryId(name) to StorageLocation(uri)
                }
        }

        override suspend fun ensureCategoryWorkspace(category: MediaCategory): StorageLocation? =
            when (category) {
                MediaCategory.IMAGE ->
                    createDefaultWorkspace(
                        folderName = IMAGE_DIRECTORY_NAME,
                        setRoot = dataSource::setImageRoot,
                    )
                MediaCategory.VOICE ->
                    createDefaultWorkspace(
                        folderName = VOICE_DIRECTORY_NAME,
                        setRoot = dataSource::setVoiceRoot,
                    )
            }

        override suspend fun allocateVoiceCaptureTarget(entryId: MediaEntryId): StorageLocation =
            StorageLocation(dataSource.createVoiceFile(entryId.raw).toString())

        override suspend fun removeVoiceCapture(entryId: MediaEntryId) {
            dataSource.deleteVoiceFile(entryId.raw)
        }

        private suspend fun createDefaultWorkspace(
            folderName: String,
            setRoot: suspend (String) -> Unit,
        ): StorageLocation? =
            try {
                val uri = dataSource.createDirectory(folderName)
                setRoot(uri)
                StorageLocation(uri)
            } catch (e: Exception) {
                Timber.tag(TAG).w(
                    e,
                    "Failed to create default media workspace: folder=%s",
                    folderName,
                )
                null
            }
    }
