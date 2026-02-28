package com.lomo.data.repository

import android.net.Uri
import com.lomo.data.source.FileDataSource
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

        private val imageUriMap = MutableStateFlow<Map<String, String>>(emptyMap())

        override suspend fun saveImage(sourceUri: String): String = dataSource.saveImage(Uri.parse(sourceUri))

        override suspend fun deleteImage(filename: String) {
            dataSource.deleteImage(filename)
        }

        override fun getImageUriMap(): Flow<Map<String, String>> = imageUriMap.asStateFlow()

        override suspend fun syncImageCache() {
            if (dataSource.getImageRootFlow().first() == null) {
                imageUriMap.value = emptyMap()
                return
            }

            imageUriMap.value = dataSource.listImageFiles().associate { (name, uri) -> name to uri }
        }

        override suspend fun createDefaultImageDirectory(): String? =
            createDefaultDirectory(
                directoryName = IMAGE_DIRECTORY_NAME,
                setRoot = dataSource::setImageRoot,
            )

        override suspend fun createVoiceFile(filename: String): String = dataSource.createVoiceFile(filename).toString()

        override suspend fun deleteVoiceFile(filename: String) {
            dataSource.deleteVoiceFile(filename)
        }

        override suspend fun createDefaultVoiceDirectory(): String? =
            createDefaultDirectory(
                directoryName = VOICE_DIRECTORY_NAME,
                setRoot = dataSource::setVoiceRoot,
            )

        private suspend fun createDefaultDirectory(
            directoryName: String,
            setRoot: suspend (String) -> Unit,
        ): String? =
            try {
                val uri = dataSource.createDirectory(directoryName)
                setRoot(uri)
                uri
            } catch (e: Exception) {
                Timber.tag(TAG).w(
                    e,
                    "Failed to create default media directory: directory=%s",
                    directoryName,
                )
                null
            }
    }
