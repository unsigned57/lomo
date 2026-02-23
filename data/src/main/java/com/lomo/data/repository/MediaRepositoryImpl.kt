package com.lomo.data.repository

import android.net.Uri
import com.lomo.data.local.dao.ImageCacheDao
import com.lomo.data.local.entity.ImageCacheEntity
import com.lomo.data.source.FileDataSource
import com.lomo.domain.repository.MediaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class MediaRepositoryImpl
    @Inject
    constructor(
        private val imageCacheDao: ImageCacheDao,
        private val dataSource: FileDataSource,
    ) : MediaRepository {
        override suspend fun saveImage(uri: Uri): String = dataSource.saveImage(uri)

        override suspend fun deleteImage(filename: String) {
            dataSource.deleteImage(filename)
        }

        override fun getImageUriMap(): Flow<Map<String, String>> =
            imageCacheDao.getAllImages().map { entities ->
                entities.associate { it.filename to it.uriString }
            }

        override suspend fun syncImageCache() {
            // Keep cache in sync via differential update instead of full clear/rebuild.
            if (dataSource.getImageRootFlow().first() == null) return

            val existingImages = imageCacheDao.getAllImagesSync()
            val existingMap = existingImages.associateBy { it.filename }
            val existingFilenames = existingMap.keys

            val newImages = dataSource.listImageFiles()
            val newFilenames = newImages.map { it.first }.toSet()

            val toInsert = newImages.filter { it.first !in existingMap }
            val toDelete = existingFilenames - newFilenames

            if (toDelete.isNotEmpty()) {
                imageCacheDao.deleteByFilenames(toDelete.toList())
            }

            if (toInsert.isNotEmpty()) {
                val cacheEntities =
                    toInsert.map { (name, uri) ->
                        ImageCacheEntity(
                            filename = name,
                            uriString = uri,
                            lastModified = System.currentTimeMillis(),
                        )
                    }
                imageCacheDao.insertImages(cacheEntities)
            }
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
