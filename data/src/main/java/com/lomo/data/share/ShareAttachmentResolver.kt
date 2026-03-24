package com.lomo.data.share

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.ShareAttachmentInfo
import com.lomo.domain.model.ShareTransferError
import com.lomo.domain.model.ShareTransferErrorPolicy
import com.lomo.domain.model.ShareTransferLimits
import kotlinx.coroutines.flow.first
import java.io.File

internal class ShareAttachmentResolver(
    private val context: Context,
    private val dataStore: LomoDataStore,
) {
    suspend fun prepareAttachments(rawAttachmentUris: Map<String, String>): AttachmentPreparationResult {
        val parsedUris = rawAttachmentUris.mapValues { (_, value) -> value.toUri() }
        val resolvedAttachmentUris = resolveAttachmentUris(parsedUris)
        val missingCount = rawAttachmentUris.size - resolvedAttachmentUris.size
        val attachmentInfos = mutableListOf<ShareAttachmentInfo>()
        var error: ShareTransferError? =
            when {
                missingCount > 0 -> ShareTransferErrorPolicy.missingAttachments(missingCount)
                !ShareTransferLimits.isAttachmentCountValid(resolvedAttachmentUris.size) -> {
                    ShareTransferErrorPolicy.tooManyAttachments()
                }

                else -> null
            }
        var totalAttachmentBytes = 0L
        if (error == null) {
            for ((name, uri) in resolvedAttachmentUris) {
                val resolvedSize = resolveAttachmentSize(uri)
                val size = resolvedSize.coerceAtLeast(0L)
                totalAttachmentBytes += size
                val type = detectAttachmentType(name, uri)
                error =
                    when {
                        resolvedSize > ShareTransferLimits.maxAttachmentPayloadBytes(e2eEnabled = false) -> {
                            ShareTransferErrorPolicy.attachmentTooLarge()
                        }

                        totalAttachmentBytes >
                            ShareTransferLimits.maxTotalAttachmentPayloadBytes(e2eEnabled = false) -> {
                            ShareTransferErrorPolicy.attachmentsTooLarge()
                        }

                        type == UNKNOWN_ATTACHMENT_TYPE -> {
                            ShareTransferErrorPolicy.unsupportedAttachmentType()
                        }

                        else -> null
                    }
                if (error != null) {
                    break
                }
                attachmentInfos += ShareAttachmentInfo(name = name, type = type, size = size)
            }
        }
        return error?.let(AttachmentPreparationResult::Failure)
            ?: AttachmentPreparationResult.Success(
                PreparedAttachments(
                    uris = resolvedAttachmentUris,
                    infos = attachmentInfos,
                ),
            )
    }

    private suspend fun resolveAttachmentUris(attachmentUris: Map<String, Uri>): Map<String, Uri> {
        if (attachmentUris.isEmpty()) return emptyMap()

        val resolved = linkedMapOf<String, Uri>()
        for ((reference, uri) in attachmentUris) {
            val resolvedUri = resolveAttachmentUri(reference, uri)
            if (resolvedUri != null) {
                resolved[reference] = resolvedUri
            }
        }
        return resolved
    }

    private suspend fun resolveAttachmentUri(
        reference: String,
        uri: Uri,
    ): Uri? {
        val normalizedReference = reference.trim()
        val fileName =
            normalizedReference
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .takeIf(String::isNotBlank)
        val absoluteUri =
            normalizedReference
                .takeIf(String::isNotBlank)
                ?.let(::File)
                ?.takeIf { it.isAbsolute && it.exists() }
                ?.let(Uri::fromFile)
        val directoryUri = fileName?.let { resolveFromConfiguredDirectories(it) }
        val treeUri = fileName?.let { resolveFromConfiguredTreeUris(it) }
        return uri.takeIf(::isDirectUri) ?: absoluteUri ?: directoryUri ?: treeUri
    }

    private suspend fun resolveFromConfiguredDirectories(fileName: String): Uri? {
        val directories =
            listOf(
                dataStore.imageDirectory.first(),
                dataStore.voiceDirectory.first(),
                dataStore.rootDirectory.first(),
            )
        for (directory in directories) {
            val resolved =
                directory
                    ?.takeIf(String::isNotBlank)
                    ?.let { File(it, fileName) }
                    ?.takeIf { it.exists() && it.isFile }
                    ?.let(Uri::fromFile)
            if (resolved != null) {
                return resolved
            }
        }
        return null
    }

    private suspend fun resolveFromConfiguredTreeUris(fileName: String): Uri? {
        val treeUris =
            listOf(
                dataStore.imageUri.first(),
                dataStore.voiceUri.first(),
                dataStore.rootUri.first(),
            )
        for (treeUriString in treeUris) {
            val resolved =
                treeUriString
                    ?.takeIf(String::isNotBlank)
                    ?.let(::findFileInTree)
                    ?.findFile(fileName)
                    ?.uri
            if (resolved != null) {
                return resolved
            }
        }
        return null
    }

    private fun findFileInTree(treeUriString: String): DocumentFile? =
        runCatching {
            DocumentFile.fromTreeUri(context, treeUriString.toUri())
        }.getOrNull()

    private fun resolveAttachmentSize(uri: Uri): Long {
        val queriedSize = queryAttachmentSize(uri)
        val fileSize =
            uri.path
                ?.takeIf { uri.scheme == FILE_SCHEME && it.isNotBlank() }
                ?.let(::File)
                ?.takeIf { it.exists() && it.isFile }
                ?.length()
                ?.coerceAtLeast(0L)
        val descriptorSize =
            runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, READ_MODE)?.use { descriptor ->
                    descriptor.length.takeIf { it >= 0L }
                }
            }.getOrNull()
        return queriedSize ?: fileSize ?: descriptorSize ?: 0L
    }

    private fun queryAttachmentSize(uri: Uri): Long? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                val hasSize = cursor.moveToFirst() && index >= 0 && !cursor.isNull(index)
                if (hasSize) cursor.getLong(index).takeIf { it >= 0L } else null
            }
        }.getOrNull()

    private fun detectAttachmentType(
        name: String,
        uri: Uri,
    ): String {
        val byName =
            when {
                name.matches(IMAGE_NAME_REGEX) -> IMAGE_TYPE
                name.matches(AUDIO_NAME_REGEX) -> AUDIO_TYPE
                else -> UNKNOWN_ATTACHMENT_TYPE
            }
        if (byName != UNKNOWN_ATTACHMENT_TYPE) {
            return byName
        }

        val mime =
            runCatching {
                context.contentResolver.getType(uri)?.lowercase()
            }.getOrNull()
        return when {
            mime?.startsWith(IMAGE_MIME_PREFIX) == true -> IMAGE_TYPE
            mime?.startsWith(AUDIO_MIME_PREFIX) == true -> AUDIO_TYPE
            else -> UNKNOWN_ATTACHMENT_TYPE
        }
    }

    private fun isDirectUri(uri: Uri): Boolean = uri.scheme == CONTENT_SCHEME || uri.scheme == FILE_SCHEME

    internal data class PreparedAttachments(
        val uris: Map<String, Uri>,
        val infos: List<ShareAttachmentInfo>,
    )

    internal sealed interface AttachmentPreparationResult {
        data class Success(
            val prepared: PreparedAttachments,
        ) : AttachmentPreparationResult

        data class Failure(
            val error: ShareTransferError,
        ) : AttachmentPreparationResult
    }

    private companion object {
        private const val AUDIO_MIME_PREFIX = "audio/"
        private const val AUDIO_TYPE = "audio"
        private const val CONTENT_SCHEME = "content"
        private const val FILE_SCHEME = "file"
        private const val IMAGE_MIME_PREFIX = "image/"
        private const val IMAGE_TYPE = "image"
        private const val READ_MODE = "r"
        private const val UNKNOWN_ATTACHMENT_TYPE = "unknown"
        private val AUDIO_NAME_REGEX = Regex(".*\\.(m4a|mp3|ogg|wav)$", RegexOption.IGNORE_CASE)
        private val IMAGE_NAME_REGEX = Regex(".*\\.(jpg|jpeg|png|webp|gif)$", RegexOption.IGNORE_CASE)
    }
}
