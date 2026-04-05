package com.lomo.data.repository

import com.lomo.data.sync.SyncDirectoryLayout
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.Locale

internal suspend fun resolveLocalSyncMode(runtime: S3SyncRepositoryContext): S3LocalSyncMode {
    val rootDirectory = runtime.dataStore.rootDirectory.first()
    val s3LocalSyncDirectory =
        runtime.dataStore.s3LocalSyncDirectory.first()?.trim()?.takeIf(String::isNotBlank)
    val rootUri = runtime.dataStore.rootUri.first()
    val imageDirectory = runtime.dataStore.imageDirectory.first()
    val imageUri = runtime.dataStore.imageUri.first()
    val voiceDirectory = runtime.dataStore.voiceDirectory.first()
    val voiceUri = runtime.dataStore.voiceUri.first()
    if (s3LocalSyncDirectory != null) {
        val memoLocation = effectiveConfiguredLocation(rootDirectory, rootUri)
        val imageLocation = effectiveConfiguredLocation(imageDirectory, imageUri)
        val voiceLocation = effectiveConfiguredLocation(voiceDirectory, voiceUri)
        return if (isContentUriRoot(s3LocalSyncDirectory)) {
            S3LocalSyncMode.SafVaultRoot(
                rootUriString = s3LocalSyncDirectory,
                memoRelativeDir = relativeConfiguredLocation(s3LocalSyncDirectory, memoLocation),
                imageRelativeDir = relativeConfiguredLocation(s3LocalSyncDirectory, imageLocation),
                voiceRelativeDir = relativeConfiguredLocation(s3LocalSyncDirectory, voiceLocation),
                legacyRemoteCompatibility = false,
            )
        } else {
            S3LocalSyncMode.FileVaultRoot(
                rootDir = File(s3LocalSyncDirectory),
                memoRelativeDir = relativeConfiguredLocation(s3LocalSyncDirectory, memoLocation),
                imageRelativeDir = relativeConfiguredLocation(s3LocalSyncDirectory, imageLocation),
                voiceRelativeDir = relativeConfiguredLocation(s3LocalSyncDirectory, voiceLocation),
                legacyRemoteCompatibility = false,
            )
        }
    }

    val imageRoot = imageDirectory?.takeIf(String::isNotBlank)?.let(::File)
    val voiceRoot = voiceDirectory?.takeIf(String::isNotBlank)?.let(::File)
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

    val memoRoot = rootDirectory?.takeIf(String::isNotBlank)?.let(::File)
    val configuredRoots = listOfNotNull(memoRoot, imageRoot, voiceRoot)
    val commonRoot = resolveCommonRoot(configuredRoots) ?: return legacyMode

    return S3LocalSyncMode.FileVaultRoot(
        rootDir = commonRoot,
        memoRelativeDir = memoRoot?.let { relativePathFrom(commonRoot, it) },
        imageRelativeDir = imageRoot?.let { relativePathFrom(commonRoot, it) },
        voiceRelativeDir = voiceRoot?.let { relativePathFrom(commonRoot, it) },
        legacyRemoteCompatibility = true,
    )
}

internal fun normalizeRemoteRelativePath(
    relativePath: String,
    layout: SyncDirectoryLayout,
    mode: S3LocalSyncMode,
): String? =
    when (mode) {
        is S3LocalSyncMode.VaultRoot -> resolveVaultRootPath(relativePath, layout, mode)

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
    val normalized =
        if (mode.legacyRemoteCompatibility) {
            normalizeLegacyCompatibleVaultRootPath(
                relativePath = sanitized,
                layout = layout,
                mode = mode,
            )
        } else {
            sanitized.takeUnless { isLegacyVaultRootCompatibilityPath(it, layout) } ?: return null
        }
    return normalized.takeIf(::isSyncableContentPath)
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
        val memoRelativeDir: String?
        val imageRelativeDir: String?
        val voiceRelativeDir: String?
        val legacyRemoteCompatibility: Boolean
    }

    data class FileVaultRoot(
        val rootDir: File,
        override val memoRelativeDir: String?,
        override val imageRelativeDir: String?,
        override val voiceRelativeDir: String?,
        override val legacyRemoteCompatibility: Boolean,
    ) : VaultRoot

    data class SafVaultRoot(
        val rootUriString: String,
        override val memoRelativeDir: String?,
        override val imageRelativeDir: String?,
        override val voiceRelativeDir: String?,
        override val legacyRemoteCompatibility: Boolean,
    ) : VaultRoot
}

internal fun S3LocalSyncMode.fingerprint(): String =
    when (this) {
        is S3LocalSyncMode.Legacy ->
            "legacy:${directImageRoot?.absolutePath.orEmpty()}:${directVoiceRoot?.absolutePath.orEmpty()}"

        is S3LocalSyncMode.FileVaultRoot ->
            "file-vault:${rootDir.absolutePath}:${memoRelativeDir.orEmpty()}:${imageRelativeDir.orEmpty()}:${voiceRelativeDir.orEmpty()}:$legacyRemoteCompatibility"

        is S3LocalSyncMode.SafVaultRoot ->
            "saf-vault:$rootUriString:${memoRelativeDir.orEmpty()}:${imageRelativeDir.orEmpty()}:${voiceRelativeDir.orEmpty()}:$legacyRemoteCompatibility"
    }

internal fun S3LocalSyncMode.VaultRoot.relativeDirectoryFor(kind: S3LocalChangeKind): String? =
    when (kind) {
        S3LocalChangeKind.MEMO -> memoRelativeDir
        S3LocalChangeKind.IMAGE -> imageRelativeDir
        S3LocalChangeKind.VOICE -> voiceRelativeDir
        S3LocalChangeKind.GENERIC -> null
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
