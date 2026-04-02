package com.lomo.data.repository

import com.lomo.data.sync.SyncDirectoryLayout
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.Locale

internal suspend fun resolveLocalSyncMode(runtime: S3SyncRepositoryContext): S3LocalSyncMode {
    val s3LocalSyncDirectory =
        runtime.dataStore.s3LocalSyncDirectory.first()?.trim()?.takeIf(String::isNotBlank)
    if (s3LocalSyncDirectory != null) {
        return if (isContentUriRoot(s3LocalSyncDirectory)) {
            S3LocalSyncMode.SafVaultRoot(rootUriString = s3LocalSyncDirectory)
        } else {
            S3LocalSyncMode.FileVaultRoot(
                rootDir = File(s3LocalSyncDirectory),
                memoRelativeDir = null,
                imageRelativeDir = null,
                voiceRelativeDir = null,
            )
        }
    }

    val rootUri = runtime.dataStore.rootUri.first()
    val imageUri = runtime.dataStore.imageUri.first()
    val voiceUri = runtime.dataStore.voiceUri.first()
    val imageRoot = runtime.dataStore.imageDirectory.first()?.takeIf(String::isNotBlank)?.let(::File)
    val voiceRoot = runtime.dataStore.voiceDirectory.first()?.takeIf(String::isNotBlank)?.let(::File)
    val legacyMode =
        S3LocalSyncMode.Legacy(
            directImageRoot = imageRoot.takeIf { imageUri.isNullOrBlank() },
            directVoiceRoot = voiceRoot.takeIf { voiceUri.isNullOrBlank() },
        )
    val hasAnyUri =
        listOf(
            rootUri,
            imageUri,
            voiceUri,
        ).any { !it.isNullOrBlank() }
    if (hasAnyUri) {
        return legacyMode
    }

    val memoRoot = runtime.dataStore.rootDirectory.first()?.takeIf(String::isNotBlank)?.let(::File)
    val configuredRoots = listOfNotNull(memoRoot, imageRoot, voiceRoot)
    val commonRoot = resolveCommonRoot(configuredRoots) ?: return legacyMode

    return S3LocalSyncMode.FileVaultRoot(
        rootDir = commonRoot,
        memoRelativeDir = memoRoot?.let { relativePathFrom(commonRoot, it) },
        imageRelativeDir = imageRoot?.let { relativePathFrom(commonRoot, it) },
        voiceRelativeDir = voiceRoot?.let { relativePathFrom(commonRoot, it) },
    )
}

internal fun normalizeRemoteRelativePath(
    relativePath: String,
    layout: SyncDirectoryLayout,
    mode: S3LocalSyncMode,
): String? =
    when (mode) {
        is S3LocalSyncMode.VaultRoot -> {
            val sanitized = sanitizeRelativePath(relativePath) ?: return null
            val withoutLegacyRoot = sanitized.removeLegacyRootPrefix()
            val mapped = mode.mapLegacyCompatiblePath(withoutLegacyRoot, layout)
            mapped.takeIf(::isSyncableContentPath)
        }

        is S3LocalSyncMode.Legacy -> {
            val sanitized = sanitizeRelativePath(relativePath) ?: return null
            sanitized.takeIf { isLegacyManagedPath(it, layout) }
        }
    }

internal fun isObviousPlaintextExternalPathForEncryptedConnectionCheck(
    relativePath: String,
    layout: SyncDirectoryLayout,
    mode: S3LocalSyncMode,
): Boolean {
    val sanitized = sanitizeRelativePath(relativePath) ?: return false
    if (normalizeRemoteRelativePath(sanitized, layout, mode) != null) {
        return false
    }
    val hasHiddenSegment = sanitized.split('/').any { segment -> segment.startsWith(".") }
    val lastSegment = sanitized.substringAfterLast('/')
    val hasFileExtension =
        lastSegment.contains('.') &&
            lastSegment.substringAfterLast('.', "").isNotBlank()
    return hasHiddenSegment || hasFileExtension
}

internal fun resolveVaultRootPath(
    path: String,
    layout: SyncDirectoryLayout,
    mode: S3LocalSyncMode.VaultRoot,
): String? {
    val sanitized = sanitizeRelativePath(path) ?: return null
    val withoutLegacyRoot = sanitized.removeLegacyRootPrefix()
    val mapped = mode.mapLegacyCompatiblePath(withoutLegacyRoot, layout)
    return mapped.takeIf(::isSyncableContentPath)
}

internal fun legacyIsMemoPath(
    path: String,
    layout: SyncDirectoryLayout,
): Boolean = path.startsWith(legacyMemoRemotePrefix(layout)) && path.endsWith(S3_MEMO_SUFFIX)

internal fun legacyExtractMemoFilename(
    path: String,
    layout: SyncDirectoryLayout,
): String = path.removePrefix(legacyMemoRemotePrefix(layout))

internal fun contentTypeForRelativePath(relativePath: String): String {
    val extension = relativePath.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when {
        relativePath.endsWith(S3_MEMO_SUFFIX) -> S3_MARKDOWN_CONTENT_TYPE
        extension in IMAGE_CONTENT_TYPES -> IMAGE_CONTENT_TYPES.getValue(extension)
        extension in VOICE_CONTENT_TYPES -> VOICE_CONTENT_TYPES.getValue(extension)
        extension in S3_SYNC_IMAGE_EXTENSIONS -> DEFAULT_IMAGE_CONTENT_TYPE
        extension in S3_SYNC_VOICE_EXTENSIONS -> DEFAULT_VOICE_CONTENT_TYPE
        else -> OCTET_STREAM
    }
}

internal sealed interface S3LocalSyncMode {
    data class Legacy(
        val directImageRoot: File? = null,
        val directVoiceRoot: File? = null,
    ) : S3LocalSyncMode

    sealed interface VaultRoot : S3LocalSyncMode {
        fun mapLegacyCompatiblePath(
            relativePath: String,
            layout: SyncDirectoryLayout,
        ): String
    }

    data class FileVaultRoot(
        val rootDir: File,
        val memoRelativeDir: String?,
        val imageRelativeDir: String?,
        val voiceRelativeDir: String?,
    ) : VaultRoot {
        override fun mapLegacyCompatiblePath(
            relativePath: String,
            layout: SyncDirectoryLayout,
        ): String =
            remapLegacyVaultRootPath(
                relativePath = relativePath,
                layout = layout,
                memoRelativeDir = memoRelativeDir,
                imageRelativeDir = imageRelativeDir,
                voiceRelativeDir = voiceRelativeDir,
            )
    }

    data class SafVaultRoot(
        val rootUriString: String,
    ) : VaultRoot {
        override fun mapLegacyCompatiblePath(
            relativePath: String,
            layout: SyncDirectoryLayout,
        ): String = remapLegacyVaultRootPath(relativePath = relativePath, layout = layout)
    }
}

private fun remapLegacyVaultRootPath(
    relativePath: String,
    layout: SyncDirectoryLayout,
    memoRelativeDir: String? = null,
    imageRelativeDir: String? = null,
    voiceRelativeDir: String? = null,
): String {
    if (relativePath.matchesLegacyFolder(layout.memoFolder)) {
        return remapLegacyFolder(relativePath, layout.memoFolder, memoRelativeDir)
    }
    if (relativePath.matchesLegacyFolder(layout.imageFolder)) {
        return remapLegacyFolder(relativePath, layout.imageFolder, imageRelativeDir)
    }
    if (relativePath.matchesLegacyFolder(layout.voiceFolder)) {
        return remapLegacyFolder(relativePath, layout.voiceFolder, voiceRelativeDir)
    }
    return relativePath
}

private fun remapLegacyFolder(
    relativePath: String,
    folder: String,
    targetRelativeDir: String?,
): String {
    val remainder = relativePath.removePrefix("$folder/").removePrefix(folder).trimStart('/')
    return joinRelativePath(targetRelativeDir, remainder)
}

private fun resolveCommonRoot(configuredRoots: List<File>): File? {
    if (configuredRoots.isEmpty()) {
        return null
    }
    var common = configuredRoots.first().absoluteFile.normalize()
    configuredRoots.drop(1).forEach { candidate ->
        val normalizedCandidate = candidate.absoluteFile.normalize()
        while (!isAncestor(common, normalizedCandidate)) {
            common = common.parentFile ?: return null
        }
    }
    if (common.parentFile == null && configuredRoots.any { it.absoluteFile.normalize() != common }) {
        return null
    }
    return common
}
