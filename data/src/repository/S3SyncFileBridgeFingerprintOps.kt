package com.lomo.data.repository

import com.lomo.data.sync.SyncDirectoryLayout

internal fun S3SyncFileBridgeScope.localFingerprintSource(layout: SyncDirectoryLayout): S3LocalFingerprintSource =
    S3LocalFingerprintSource { path, _ -> computeLocalFingerprint(path, layout) }

/**
 * Local file snapshot for pending conflict/review side validation. Enumeration is metadata-only,
 * so the content fingerprint is resolved here exactly when the persisted side metadata recorded
 * one and a content comparison is therefore required.
 */
internal suspend fun S3SyncFileBridgeScope.pendingValidationLocalFile(
    path: String,
    layout: SyncDirectoryLayout,
    requireContentFingerprint: Boolean,
): LocalS3File? =
    localFile(path, layout)?.let { local ->
        if (!requireContentFingerprint || local.localFingerprint != null) {
            local
        } else {
            local.copy(localFingerprint = computeLocalFingerprint(path, layout))
        }
    }

internal suspend fun S3SyncFileBridgeScope.computeLocalFingerprint(
    path: String,
    layout: SyncDirectoryLayout,
): String? =
    when (val scopeMode = mode) {
        is S3LocalSyncMode.FileVaultRoot ->
            resolveVaultRootPath(path, layout, scopeMode)?.let { relativePath ->
                computeFileVaultRootFingerprint(scopeMode, relativePath)
            }

        is S3LocalSyncMode.SafVaultRoot ->
            resolveVaultRootPath(path, layout, scopeMode)?.let { relativePath ->
                safTreeAccess.md5Hex(scopeMode.rootUriString, relativePath.value)
            }

        is S3LocalSyncMode.Legacy -> legacyComputeLocalFingerprint(runtime, path, layout, scopeMode)
    }
