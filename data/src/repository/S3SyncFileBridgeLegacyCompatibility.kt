package com.lomo.data.repository

import com.lomo.data.sync.SyncDirectoryLayout

internal fun isLegacyVaultRootCompatibilityPath(
    path: String,
    layout: SyncDirectoryLayout,
): Boolean {
    val relativePath = path.removePrefix("$S3_ROOT/")
    if (relativePath == path) {
        return false
    }
    return when {
        legacyMemoFolders(layout).any(relativePath::matchesLegacyFolder) ->
            relativePath.endsWith(S3_MEMO_SUFFIX)

        legacyImageFolders(layout).any(relativePath::matchesLegacyFolder) ->
            isSupportedAttachmentPath(relativePath)

        legacyVoiceFolders(layout).any(relativePath::matchesLegacyFolder) ->
            isSupportedAttachmentPath(relativePath)

        else -> false
    }
}

internal fun normalizeLegacyCompatibleVaultRootPath(
    relativePath: String,
    layout: SyncDirectoryLayout,
    mode: S3LocalSyncMode.VaultRoot,
): String {
    val withoutLegacyRoot = relativePath.removeLegacyRootPrefix()
    return remapLegacyFolder(
        relativePath = withoutLegacyRoot,
        folders = legacyMemoFolders(layout),
        targetRelativeDir = mode.memoRelativeDir,
    ) ?: remapLegacyFolder(
        relativePath = withoutLegacyRoot,
        folders = legacyImageFolders(layout),
        targetRelativeDir = mode.imageRelativeDir,
    ) ?: remapLegacyFolder(
        relativePath = withoutLegacyRoot,
        folders = legacyVoiceFolders(layout),
        targetRelativeDir = mode.voiceRelativeDir,
    ) ?: withoutLegacyRoot
}

private fun legacyMemoFolders(layout: SyncDirectoryLayout): Set<String> =
    setOf(layout.memoFolder, DEFAULT_LEGACY_MEMO_FOLDER)

private fun legacyImageFolders(layout: SyncDirectoryLayout): Set<String> =
    setOf(layout.imageFolder, DEFAULT_LEGACY_IMAGE_FOLDER)

private fun legacyVoiceFolders(layout: SyncDirectoryLayout): Set<String> =
    setOf(layout.voiceFolder, DEFAULT_LEGACY_VOICE_FOLDER)

private fun String.removeLegacyRootPrefix(): String =
    when {
        startsWith("$S3_ROOT/") -> removePrefix("$S3_ROOT/")
        this == S3_ROOT -> ""
        else -> this
    }

private fun remapLegacyFolder(
    relativePath: String,
    folders: Set<String>,
    targetRelativeDir: String?,
): String? {
    val matchedFolder = folders.firstOrNull(relativePath::matchesLegacyFolder) ?: return null
    val remainder =
        relativePath
            .removePrefix("$matchedFolder/")
            .removePrefix(matchedFolder)
            .trimStart('/')
    return joinRelativePath(targetRelativeDir, remainder)
}

private const val DEFAULT_LEGACY_MEMO_FOLDER = "memo"
private const val DEFAULT_LEGACY_IMAGE_FOLDER = "images"
private const val DEFAULT_LEGACY_VOICE_FOLDER = "voice"
