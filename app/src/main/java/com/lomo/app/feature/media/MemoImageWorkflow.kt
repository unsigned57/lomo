package com.lomo.app.feature.media

import android.net.Uri
import com.lomo.domain.repository.MediaRepository
import javax.inject.Inject

class MemoImageWorkflow
    @Inject
    constructor(
        private val mediaRepository: MediaRepository,
    ) {
        suspend fun saveImageAndSync(uri: Uri): String {
            val path = mediaRepository.saveImage(uri)
            mediaRepository.syncImageCache()
            return path
        }

        suspend fun syncImageCacheBestEffort() {
            try {
                mediaRepository.syncImageCache()
            } catch (_: Exception) {
                // Best effort refresh.
            }
        }
    }
