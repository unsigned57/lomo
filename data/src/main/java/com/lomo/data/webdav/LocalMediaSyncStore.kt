package com.lomo.data.webdav

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.sync.SyncDirectoryLayout
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalMediaSyncStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dataStore: LomoDataStore,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private val configuredRootsState: StateFlow<List<MediaRoot>?> =
            combine(
                dataStore.imageDirectory,
                dataStore.imageUri,
                dataStore.voiceDirectory,
                dataStore.voiceUri,
            ) { imageDirectory, imageUri, voiceDirectory, voiceUri ->
                buildConfiguredRoots(
                    imageDirectory = imageDirectory,
                    imageUri = imageUri,
                    voiceDirectory = voiceDirectory,
                    voiceUri = voiceUri,
                )
            }.stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = null,
            )

        suspend fun configuredCategories(): Set<MediaSyncCategory> = configuredRoots().mapTo(linkedSetOf()) { it.category }

        suspend fun listFiles(layout: SyncDirectoryLayout): Map<String, LocalMediaSyncFile> =
            withContext(Dispatchers.IO) {
                configuredRoots()
                    .flatMap { root ->
                        when (root) {
                            is MediaRoot.Direct -> listDirectFiles(root, layout)
                            is MediaRoot.Saf -> listSafFiles(root, layout)
                        }
                    }.associateBy { it.relativePath }
            }

        suspend fun readBytes(
            relativePath: String,
            layout: SyncDirectoryLayout,
        ): ByteArray =
            withContext(Dispatchers.IO) {
                val located = locate(relativePath, layout) ?: throw IOException("Media file not found: $relativePath")
                when (val root = located.root) {
                    is MediaRoot.Direct -> {
                        File(root.path, located.filename).readBytes()
                    }

                    is MediaRoot.Saf -> {
                        val document =
                            requireNotNull(getSafRoot(root)?.findFile(located.filename)) {
                                "Media file not found: $relativePath"
                            }
                        context.contentResolver.openInputStream(document.uri)?.use { it.readBytes() }
                            ?: throw IOException("Cannot open media file: $relativePath")
                    }
                }
            }

        suspend fun writeBytes(
            relativePath: String,
            bytes: ByteArray,
            layout: SyncDirectoryLayout,
        ) {
            withContext(Dispatchers.IO) {
                val located = locate(relativePath, layout) ?: throw IOException("Media root not configured for: $relativePath")
                when (val root = located.root) {
                    is MediaRoot.Direct -> {
                        val directory = File(root.path)
                        if (!directory.exists()) directory.mkdirs()
                        val file = File(directory, located.filename)
                        file.parentFile?.mkdirs()
                        file.writeBytes(bytes)
                    }

                    is MediaRoot.Saf -> {
                        val directory = getSafRoot(root) ?: throw IOException("Cannot access media directory for: $relativePath")
                        val target =
                            directory.findFile(located.filename)
                                ?: directory.createFile(contentTypeFor(located.filename, root.category), located.filename)
                                ?: throw IOException("Cannot create media file: $relativePath")
                        context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                            output.write(bytes)
                        } ?: throw IOException("Cannot write media file: $relativePath")
                    }
                }
            }
        }

        suspend fun delete(
            relativePath: String,
            layout: SyncDirectoryLayout,
        ) {
            withContext(Dispatchers.IO) {
                val located = locate(relativePath, layout) ?: return@withContext
                when (val root = located.root) {
                    is MediaRoot.Direct -> File(root.path, located.filename).delete()
                    is MediaRoot.Saf -> getSafRoot(root)?.findFile(located.filename)?.delete()
                }
            }
        }

        fun isMediaPath(
            path: String,
            layout: SyncDirectoryLayout,
        ): Boolean {
            val stripped = stripSyncPrefix(path)
            return stripped.startsWith("${layout.imageFolder}/") ||
                stripped.startsWith("${layout.voiceFolder}/")
        }

        fun contentTypeForPath(
            path: String,
            layout: SyncDirectoryLayout,
        ): String {
            val stripped = stripSyncPrefix(path)
            val category =
                when {
                    stripped.startsWith("${layout.imageFolder}/") -> MediaSyncCategory.IMAGE
                    stripped.startsWith("${layout.voiceFolder}/") -> MediaSyncCategory.VOICE
                    else -> return OCTET_STREAM
                }
            return contentTypeFor(stripped.substringAfterLast('/'), category)
        }

        private suspend fun configuredRoots(): List<MediaRoot> {
            configuredRootsState.value?.let { roots ->
                return roots
            }
            return buildConfiguredRoots(
                imageDirectory = dataStore.imageDirectory.first(),
                imageUri = dataStore.imageUri.first(),
                voiceDirectory = dataStore.voiceDirectory.first(),
                voiceUri = dataStore.voiceUri.first(),
            )
        }

        private fun buildConfiguredRoots(
            imageDirectory: String?,
            imageUri: String?,
            voiceDirectory: String?,
            voiceUri: String?,
        ): List<MediaRoot> =
            buildList {
                resolveRoot(MediaSyncCategory.IMAGE, imageDirectory, imageUri)?.let(::add)
                resolveRoot(MediaSyncCategory.VOICE, voiceDirectory, voiceUri)?.let(::add)
            }

        private fun resolveRoot(
            category: MediaSyncCategory,
            directory: String?,
            uri: String?,
        ): MediaRoot? =
            when {
                !directory.isNullOrBlank() -> MediaRoot.Direct(category, directory)
                !uri.isNullOrBlank() -> MediaRoot.Saf(category, Uri.parse(uri))
                else -> null
            }

        private fun listDirectFiles(
            root: MediaRoot.Direct,
            layout: SyncDirectoryLayout,
        ): List<LocalMediaSyncFile> {
            val directory = File(root.path)
            if (!directory.exists() || !directory.isDirectory) return emptyList()
            val folder = root.category.remoteFolder(layout)
            return directory
                .listFiles()
                ?.asSequence()
                ?.filter { file -> file.isFile && accepts(root.category, file.name) }
                ?.map { file ->
                    LocalMediaSyncFile(
                        relativePath = "$folder/${file.name}",
                        lastModified = file.lastModified(),
                    )
                }?.toList()
                ?: emptyList()
        }

        private fun listSafFiles(
            root: MediaRoot.Saf,
            layout: SyncDirectoryLayout,
        ): List<LocalMediaSyncFile> {
            val directory = getSafRoot(root) ?: return emptyList()
            val folder = root.category.remoteFolder(layout)
            return directory.listFiles().mapNotNull { document ->
                val name = document.name ?: return@mapNotNull null
                if (!document.isFile || !accepts(root.category, name, document.type)) return@mapNotNull null
                LocalMediaSyncFile(
                    relativePath = "$folder/$name",
                    lastModified = document.lastModified(),
                )
            }
        }

        private suspend fun locate(
            relativePath: String,
            layout: SyncDirectoryLayout,
        ): LocatedMediaFile? {
            val stripped = stripSyncPrefix(relativePath.trim().trimStart('/'))
            val category =
                when {
                    stripped.startsWith("${layout.imageFolder}/") -> MediaSyncCategory.IMAGE
                    stripped.startsWith("${layout.voiceFolder}/") -> MediaSyncCategory.VOICE
                    else -> return null
                }
            val filename = stripped.substringAfter('/')
            if (filename.isBlank()) return null
            val root = configuredRoots().firstOrNull { it.category == category } ?: return null
            return LocatedMediaFile(root = root, filename = filename)
        }

        private fun getSafRoot(root: MediaRoot.Saf): DocumentFile? =
            try {
                DocumentFile.fromTreeUri(context, root.uri)
            } catch (_: Exception) {
                null
            }

        private fun accepts(
            category: MediaSyncCategory,
            filename: String,
            mimeType: String? = null,
        ): Boolean =
            when (category) {
                MediaSyncCategory.IMAGE -> mimeType?.startsWith("image/") == true || filename.hasExtension(IMAGE_EXTENSIONS)
                MediaSyncCategory.VOICE -> mimeType?.startsWith("audio/") == true || filename.hasExtension(VOICE_EXTENSIONS)
            }

        private fun contentTypeFor(
            filename: String,
            category: MediaSyncCategory,
        ): String =
            when (category) {
                MediaSyncCategory.IMAGE -> {
                    when (filename.substringAfterLast('.', "").lowercase()) {
                        "png" -> "image/png"
                        "gif" -> "image/gif"
                        "webp" -> "image/webp"
                        "bmp" -> "image/bmp"
                        "heic" -> "image/heic"
                        "heif" -> "image/heif"
                        "avif" -> "image/avif"
                        else -> "image/jpeg"
                    }
                }

                MediaSyncCategory.VOICE -> {
                    when (filename.substringAfterLast('.', "").lowercase()) {
                        "mp3" -> "audio/mpeg"
                        "aac" -> "audio/aac"
                        "wav" -> "audio/wav"
                        "ogg" -> "audio/ogg"
                        else -> "audio/mp4"
                    }
                }
            }

        private fun String.hasExtension(extensions: Set<String>): Boolean =
            substringAfterLast('.', "").lowercase().let { it.isNotBlank() && it in extensions }

        private sealed interface MediaRoot {
            val category: MediaSyncCategory

            data class Direct(
                override val category: MediaSyncCategory,
                val path: String,
            ) : MediaRoot

            data class Saf(
                override val category: MediaSyncCategory,
                val uri: Uri,
            ) : MediaRoot
        }

        private data class LocatedMediaFile(
            val root: MediaRoot,
            val filename: String,
        )

        companion object {
            private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
            private val VOICE_EXTENSIONS = setOf("m4a", "mp3", "aac", "wav", "ogg")
            private const val OCTET_STREAM = "application/octet-stream"
            private const val WEBDAV_PREFIX = "lomo/"

            /**
             * Strips the WebDAV `lomo/` prefix from a path if present, so that
             * category-folder matching works uniformly for both Git and WebDAV paths.
             */
            fun stripSyncPrefix(path: String): String =
                if (path.startsWith(WEBDAV_PREFIX)) path.removePrefix(WEBDAV_PREFIX) else path
        }
    }

enum class MediaSyncCategory {
    IMAGE,
    VOICE,
    ;

    fun remoteFolder(layout: SyncDirectoryLayout): String =
        when (this) {
            IMAGE -> layout.imageFolder
            VOICE -> layout.voiceFolder
        }
}

data class LocalMediaSyncFile(
    val relativePath: String,
    val lastModified: Long,
)
