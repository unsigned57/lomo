package com.lomo.data.repository

import com.lomo.data.sync.SyncDirectoryLayout

internal fun isIgnoredExternalPathForConnectionCheck(
    path: String,
    layout: SyncDirectoryLayout,
    mode: S3LocalSyncMode,
): Boolean = normalizeRemoteRelativePath(path, layout, mode) == null

internal fun isMemoPath(
    path: String,
    layout: SyncDirectoryLayout,
    mode: S3LocalSyncMode,
): Boolean =
    when (mode) {
        is S3LocalSyncMode.VaultRoot ->
            resolveVaultRootPath(path, layout, mode)?.endsWith(S3_MEMO_SUFFIX) == true

        is S3LocalSyncMode.Legacy -> legacyIsMemoPath(path, layout)
    }

internal fun extractMemoFilename(
    path: String,
    layout: SyncDirectoryLayout,
    mode: S3LocalSyncMode,
): String =
    when (mode) {
        is S3LocalSyncMode.VaultRoot -> resolveVaultRootPath(path, layout, mode) ?: path
        is S3LocalSyncMode.Legacy -> legacyExtractMemoFilename(path, layout)
    }

internal fun resolveMemoRefreshTarget(
    path: String,
    layout: SyncDirectoryLayout,
    mode: S3LocalSyncMode,
): String? {
    return when (mode) {
        is S3LocalSyncMode.VaultRoot -> {
            val mapped = resolveVaultRootPath(path, layout, mode) ?: return null
            if (!mapped.endsWith(S3_MEMO_SUFFIX)) {
                return null
            }
            val memoRoot = mode.memoRelativeDir ?: return null
            when {
                memoRoot.isBlank() -> mapped
                mapped == memoRoot -> null
                mapped.startsWith("$memoRoot/") -> mapped.removePrefix("$memoRoot/")
                else -> null
            }?.takeIf(String::isNotBlank)
        }

        is S3LocalSyncMode.Legacy ->
            legacyExtractMemoFilename(path, layout)
                .takeIf { legacyIsMemoPath(path, layout) }
    }
}

internal fun contentTypeForPath(
    path: String,
    layout: SyncDirectoryLayout,
    runtime: S3SyncRepositoryContext,
    mode: S3LocalSyncMode,
): String =
    when {
        mode is S3LocalSyncMode.VaultRoot &&
            resolveVaultRootPath(path, layout, mode)?.endsWith(S3_MEMO_SUFFIX) == true ->
            S3_MARKDOWN_CONTENT_TYPE

        mode is S3LocalSyncMode.Legacy && legacyIsMemoPath(path, layout) ->
            S3_MARKDOWN_CONTENT_TYPE

        mode is S3LocalSyncMode.VaultRoot ->
            contentTypeForRelativePath(resolveVaultRootPath(path, layout, mode) ?: path)

        else -> runtime.localMediaSyncStore.contentTypeForPath(path, layout)
    }
