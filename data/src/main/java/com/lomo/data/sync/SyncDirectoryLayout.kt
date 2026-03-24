package com.lomo.data.sync

import android.net.Uri
import com.lomo.data.local.datastore.LomoDataStore
import kotlinx.coroutines.flow.first

/**
 * Describes how files should be laid out on the sync remote (WebDAV / Git).
 *
 * Folder names are derived from the last path segment of the user-configured directories.
 * When every configured directory points to the same location [allSameDirectory] is `true`
 * and callers may flatten the structure (no subdirectories).
 */
data class SyncDirectoryLayout(
    val memoFolder: String,
    val imageFolder: String,
    val voiceFolder: String,
    val allSameDirectory: Boolean,
) {
    /** The set of distinct folder names that need to be created. */
    val distinctFolders: Set<String>
        get() = linkedSetOf(memoFolder, imageFolder, voiceFolder)

    companion object {
        private const val DEFAULT_MEMO_FOLDER = "memo"
        private const val DEFAULT_IMAGE_FOLDER = "images"
        private const val DEFAULT_VOICE_FOLDER = "voice"

        suspend fun resolve(dataStore: LomoDataStore): SyncDirectoryLayout {
            val rootDir = dataStore.rootDirectory.first()
            val rootUri = dataStore.rootUri.first()
            val imageDir = dataStore.imageDirectory.first()
            val imageUri = dataStore.imageUri.first()
            val voiceDir = dataStore.voiceDirectory.first()
            val voiceUri = dataStore.voiceUri.first()

            val memoPath = effectivePath(rootDir, rootUri)
            val imagePath = effectivePath(imageDir, imageUri)
            val voicePath = effectivePath(voiceDir, voiceUri)

            val memoFolder = lastSegment(memoPath) ?: DEFAULT_MEMO_FOLDER
            val imageFolder = lastSegment(imagePath) ?: DEFAULT_IMAGE_FOLDER
            val voiceFolder = lastSegment(voicePath) ?: DEFAULT_VOICE_FOLDER

            val normalizedMemo = normalizePath(memoPath)
            val normalizedImage = normalizePath(imagePath)
            val normalizedVoice = normalizePath(voicePath)

            val allSame = normalizedMemo != null &&
                normalizedMemo == normalizedImage &&
                normalizedMemo == normalizedVoice

            return SyncDirectoryLayout(
                memoFolder = memoFolder,
                imageFolder = imageFolder,
                voiceFolder = voiceFolder,
                allSameDirectory = allSame,
            )
        }

        private fun effectivePath(directory: String?, uri: String?): String? =
            directory?.takeIf { it.isNotBlank() }
                ?: uri?.takeIf { it.isNotBlank() }

        /**
         * Extracts the last meaningful path segment from a filesystem path or content URI.
         */
        private fun lastSegment(pathOrUri: String?): String? =
            pathOrUri
                ?.takeUnless(String::isBlank)
                ?.let { path ->
                    val normalizedPath =
                        if (path.startsWith("content://")) {
                            val decoded = Uri.decode(Uri.parse(path).lastPathSegment.orEmpty())
                            decoded.substringAfter(':', decoded)
                        } else {
                            path
                        }
                    normalizedPath.trimEnd('/').substringAfterLast('/').takeIf(String::isNotBlank)
                }

        /**
         * Normalizes a path/URI to a canonical form for comparison.
         */
        private fun normalizePath(pathOrUri: String?): String? =
            pathOrUri
                ?.takeUnless(String::isBlank)
                ?.let { path ->
                    if (path.startsWith("content://")) {
                        Uri.parse(path).toString().trimEnd('/')
                    } else {
                        path.trimEnd('/')
                    }
                }
    }
}
