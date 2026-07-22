package com.lomo.data.share

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.source.MediaStorageDataSource
import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

class ShareAttachmentStorage(
    private val context: Context,
    private val dataSource: MediaStorageDataSource,
    private val dataStore: LomoDataStore,
    private val mediaRepository: MediaRepository,
) {
        /**
         * Saves a received attachment and returns the stored filename for memo remapping.
         *
         * Images and audio go through [MediaRepository.importImage] (Rust media owner via MediaEdge
         * stage→promote). Product markdown still uses basename destinations for both kinds.
         */
        suspend fun saveAttachmentFile(
            name: String,
            type: String,
            payloadFile: File,
        ): String? {
            val safeName = sanitizeAttachmentFilename(name)
            return runNonFatalCatching {
                val tempUri = Uri.fromFile(payloadFile)
                when (type) {
                    "image", "audio" -> {
                        // Shared Rust stage/promote path (magic/mime/digest owned by media owner).
                        mediaRepository.importImage(StorageLocation(tempUri.toString())).raw
                    }

                    else -> {
                        null
                    }
                }
            }.getOrElse { error ->
                Timber.tag(TAG).e(error, "Failed to save attachment: $safeName")
                null
            }
        }

        suspend fun deleteSavedAttachment(
            savedPath: String,
            type: String,
        ) {
            val filename =
                savedPath
                    .substringAfterLast('/')
                    .substringAfterLast('\\')
                    .trim()
            if (filename.isBlank()) return
            when (type) {
                "image", "audio" -> mediaRepository.removeImage(MediaEntryId(filename))
                else -> Unit
            }
        }

        internal suspend fun resolveAvailableAttachmentFilename(
            type: String,
            preferredName: String,
        ): String {
            if (type != "audio") return preferredName

            val (baseName, extension) = splitFilename(preferredName)
            var candidate = preferredName
            var suffix = 1
            while (audioAttachmentExists(candidate)) {
                candidate =
                    if (extension.isBlank()) {
                        "${baseName}_$suffix"
                    } else {
                        "${baseName}_$suffix.$extension"
                    }
                suffix += 1
            }
            return candidate
        }

        private fun splitFilename(filename: String): Pair<String, String> {
            val dotIndex = filename.lastIndexOf('.')
            if (dotIndex <= 0 || dotIndex == filename.lastIndex) {
                return filename to ""
            }
            return filename.substring(0, dotIndex) to filename.substring(dotIndex + 1)
        }

        private suspend fun audioAttachmentExists(filename: String): Boolean =
            withContext(Dispatchers.IO) {
                fileExistsInDirectory(dataStore.voiceDirectory.first(), filename) ||
                    fileExistsInDirectory(dataStore.rootDirectory.first(), filename) ||
                    fileExistsInTree(dataStore.voiceUri.first(), filename) ||
                    fileExistsInTree(dataStore.rootUri.first(), filename)
            }

        private fun fileExistsInDirectory(
            directoryPath: String?,
            filename: String,
        ): Boolean {
            if (directoryPath.isNullOrBlank()) return false
            return File(directoryPath, filename).exists()
        }

        private fun fileExistsInTree(
            treeUri: String?,
            filename: String,
        ): Boolean {
            if (treeUri.isNullOrBlank()) return false
            val root = DocumentFile.fromTreeUri(context, treeUri.toUri()) ?: return false
            return root.findFile(filename)?.exists() == true
        }

        private fun sanitizeAttachmentFilename(name: String): String {
            val trimmed = name.trim()
            if (trimmed.isBlank()) {
                return "attachment_${System.currentTimeMillis()}"
            }
            return trimmed
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .replace(Regex("""[^\w.\-]+"""), "_")
                .ifBlank { "attachment_${System.currentTimeMillis()}" }
        }

        companion object {
            private const val TAG = "ShareAttachmentStorage"
        }
}
