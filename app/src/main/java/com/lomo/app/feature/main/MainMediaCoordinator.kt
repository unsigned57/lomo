package com.lomo.app.feature.main

import android.net.Uri
import com.lomo.domain.repository.MediaRepository
import javax.inject.Inject

class MainMediaCoordinator
    @Inject
    constructor(
        private val mediaRepository: MediaRepository,
    ) {
        private val ephemeralImageFilenames = mutableSetOf<String>()

        suspend fun createDefaultDirectories(
            forImage: Boolean,
            forVoice: Boolean,
        ) {
            if (forImage) {
                mediaRepository.createDefaultImageDirectory()
            }
            if (forVoice) {
                mediaRepository.createDefaultVoiceDirectory()
            }
        }

        suspend fun saveImageAndTrack(uri: Uri): String {
            val path = mediaRepository.saveImage(uri.toString())
            mediaRepository.syncImageCache()
            ephemeralImageFilenames.add(path)
            return path
        }

        fun clearTrackedImages() {
            ephemeralImageFilenames.clear()
        }

        suspend fun discardTrackedImages() {
            val toDelete = ephemeralImageFilenames.toList()
            ephemeralImageFilenames.clear()

            toDelete.forEach { filename ->
                try {
                    mediaRepository.deleteImage(filename)
                } catch (_: Exception) {
                    // Best-effort cleanup.
                }
            }
            syncImageCacheBestEffort()
        }

        suspend fun syncImageCacheBestEffort() {
            try {
                mediaRepository.syncImageCache()
            } catch (_: Exception) {
                // Best effort refresh.
            }
        }
    }
