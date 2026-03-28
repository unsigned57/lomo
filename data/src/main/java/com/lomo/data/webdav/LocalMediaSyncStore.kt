package com.lomo.data.webdav

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
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

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
private val VOICE_EXTENSIONS = setOf("m4a", "mp3", "aac", "wav", "ogg")
private val IMAGE_CONTENT_TYPES =
    mapOf(
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "avif" to "image/avif",
    )
private val VOICE_CONTENT_TYPES =
    mapOf(
        "mp3" to "audio/mpeg",
        "aac" to "audio/aac",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
    )
private const val DEFAULT_IMAGE_CONTENT_TYPE = "image/jpeg"
private const val DEFAULT_VOICE_CONTENT_TYPE = "audio/mp4"
private const val OCTET_STREAM = "application/octet-stream"
private const val WEBDAV_PREFIX = "lomo/"

internal sealed interface MediaRoot {
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

internal data class LocatedMediaFile(
    val root: MediaRoot,
    val filename: String,
)

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

        suspend fun configuredCategories(): Set<MediaSyncCategory> =
            configuredRoots().mapTo(linkedSetOf()) { it.category }

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
                            requireNotNull(getSafRoot(context, root)?.findFile(located.filename)) {
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
                val located = requireLocatedMediaFile(locate(relativePath, layout), relativePath)
                when (val root = located.root) {
                    is MediaRoot.Direct -> {
                        val directory = File(root.path)
                        if (!directory.exists()) directory.mkdirs()
                        val file = File(directory, located.filename)
                        file.parentFile?.mkdirs()
                        file.writeBytes(bytes)
                    }

                    is MediaRoot.Saf -> {
                        val directory = requireSafMediaRoot(context, root, relativePath)
                        val target = resolveOrCreateSafTarget(directory, located, relativePath)
                        openRequiredOutputStream(context, target.uri, relativePath).use { output ->
                            output.write(bytes)
                        }
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
                    is MediaRoot.Saf -> getSafRoot(context, root)?.findFile(located.filename)?.delete()
                }
            }
        }

        fun isMediaPath(
            path: String,
            layout: SyncDirectoryLayout,
        ): Boolean = mediaCategoryForPath(stripSyncPrefix(path), layout) != null

        fun contentTypeForPath(
            path: String,
            layout: SyncDirectoryLayout,
        ): String {
            val stripped = stripSyncPrefix(path)
            val category = mediaCategoryForPath(stripped, layout) ?: return OCTET_STREAM
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
            val directory = getSafRoot(context, root) ?: return emptyList()
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
            val category = mediaCategoryForPath(stripped, layout)
            val filename = stripped.substringAfter('/', "")
            val root =
                category?.let { resolvedCategory ->
                    configuredRoots().firstOrNull { it.category == resolvedCategory }
                }
            return if (filename.isNotBlank() && root != null) {
                LocatedMediaFile(root = root, filename = filename)
            } else {
                null
            }
        }
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
        !uri.isNullOrBlank() -> MediaRoot.Saf(category, uri.toUri())
        else -> null
    }

internal fun getSafRoot(
    context: Context,
    root: MediaRoot.Saf,
): DocumentFile? =
    try {
        DocumentFile.fromTreeUri(context, root.uri)
    } catch (_: Exception) {
        null
    }

private fun resolveOrCreateSafTarget(
    directory: DocumentFile,
    located: LocatedMediaFile,
    relativePath: String,
): DocumentFile =
    directory.findFile(located.filename)
        ?: directory.createFile(
            contentTypeFor(located.filename, located.root.category),
            located.filename,
        )
        ?: throw IOException("Cannot create media file: $relativePath")

private fun accepts(
    category: MediaSyncCategory,
    filename: String,
    mimeType: String? = null,
): Boolean =
    when (category) {
        MediaSyncCategory.IMAGE ->
            mimeType?.startsWith("image/") == true || filename.hasExtension(IMAGE_EXTENSIONS)

        MediaSyncCategory.VOICE ->
            mimeType?.startsWith("audio/") == true || filename.hasExtension(VOICE_EXTENSIONS)
    }

private fun contentTypeFor(
    filename: String,
    category: MediaSyncCategory,
): String {
    val extension = filename.extensionOrEmpty()
    return when (category) {
        MediaSyncCategory.IMAGE -> IMAGE_CONTENT_TYPES[extension] ?: DEFAULT_IMAGE_CONTENT_TYPE
        MediaSyncCategory.VOICE -> VOICE_CONTENT_TYPES[extension] ?: DEFAULT_VOICE_CONTENT_TYPE
    }
}

private fun mediaCategoryForPath(
    path: String,
    layout: SyncDirectoryLayout,
): MediaSyncCategory? =
    when {
        path.startsWith(folderPrefix(layout.imageFolder)) -> MediaSyncCategory.IMAGE
        path.startsWith(folderPrefix(layout.voiceFolder)) -> MediaSyncCategory.VOICE
        else -> null
    }

private fun stripSyncPrefix(path: String): String =
    if (path.startsWith(WEBDAV_PREFIX)) path.removePrefix(WEBDAV_PREFIX) else path

private fun folderPrefix(folder: String): String = "$folder/"

private fun String.hasExtension(extensions: Set<String>): Boolean =
    extensionOrEmpty().let { it.isNotBlank() && it in extensions }

private fun String.extensionOrEmpty(): String = substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)

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
