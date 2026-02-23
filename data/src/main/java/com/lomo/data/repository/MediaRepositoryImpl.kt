package com.lomo.data.repository

import android.net.Uri
import com.lomo.data.source.FileDataSource
import com.lomo.domain.repository.MediaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class MediaRepositoryImpl
    @Inject
    constructor(
        private val dataSource: FileDataSource,
    ) : MediaRepository {
        private val imageUriMap = MutableStateFlow<Map<String, String>>(emptyMap())

        override suspend fun saveImage(uri: Uri): String = dataSource.saveImage(uri)

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
            try {
                val uri = dataSource.createDirectory("images")
                dataSource.setImageRoot(uri)
                uri
            } catch (e: Exception) {
                null
            }

        override suspend fun createVoiceFile(filename: String): Uri = dataSource.createVoiceFile(filename)

        override suspend fun deleteVoiceFile(filename: String) {
            dataSource.deleteVoiceFile(filename)
        }

        override suspend fun createDefaultVoiceDirectory(): String? =
            try {
                val uri = dataSource.createDirectory("voice")
                dataSource.setVoiceRoot(uri)
                uri
            } catch (e: Exception) {
                null
            }
    }
