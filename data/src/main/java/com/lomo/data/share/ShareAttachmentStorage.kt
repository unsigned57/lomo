package com.lomo.data.share

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.source.MediaStorageDataSource
import com.lomo.data.util.runNonFatalCatching
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

class ShareAttachmentStorage
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dataSource: MediaStorageDataSource,
        private val dataStore: LomoDataStore,
    ) {
        /**
         * Saves a received attachment and returns the stored filename for memo remapping.
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
                    "image" -> {
                        dataSource.saveImage(tempUri)
                    }

                    "audio" -> {
                        val availableName = resolveAvailableAttachmentFilename(type, safeName)
                        val voiceUri = dataSource.createVoiceFile(availableName)
                        val output =
                            context.contentResolver.openOutputStream(voiceUri)
                                ?: throw IllegalStateException("Cannot open voice output stream")
                        payloadFile.inputStream().buffered().use { input ->
                            output.use { out ->
                                input.copyTo(out)
                            }
                        }
                        availableName
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
            directory: String?,
            filename: String,
        ): Boolean {
            if (directory.isNullOrBlank()) return false
            val file = File(directory, filename)
            return file.exists() && file.isFile
        }

        private fun fileExistsInTree(
            treeUriString: String?,
            filename: String,
        ): Boolean =
            treeUriString
                ?.takeIf(String::isNotBlank)
                ?.let { runCatching { DocumentFile.fromTreeUri(context, it.toUri()) }.getOrNull() }
                ?.findFile(filename)
                ?.isFile == true

        private fun sanitizeAttachmentFilename(name: String): String {
            val base =
                name
                    .substringAfterLast('/')
                    .substringAfterLast('\\')
                    .trim()
            val sanitized =
                base
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .take(MAX_SANITIZED_FILENAME_CHARS)
            return if (sanitized.isBlank()) {
                "attachment_${System.currentTimeMillis()}"
            } else {
                sanitized
            }
        }

        private companion object {
            private const val MAX_SANITIZED_FILENAME_CHARS = 96
            private const val TAG = "ShareAttachmentStorage"
        }
    }
