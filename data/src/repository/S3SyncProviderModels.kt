package com.lomo.data.repository

import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncOutcome
import com.lomo.domain.model.S3SyncReason

data class LocalS3File(
    val path: String,
    val lastModified: Long,
    val size: Long? = null,
    val localFingerprint: String? = null,
)

data class RemoteS3File(
    val path: String,
    val etag: String?,
    val lastModified: Long?,
    val size: Long? = null,
    val contentMd5: String? = null,
    val remotePath: String = path,
    val verificationLevel: S3RemoteVerificationLevel = S3RemoteVerificationLevel.VERIFIED_REMOTE,
)

val RemoteS3File.verified: Boolean
    get() = verificationLevel == S3RemoteVerificationLevel.VERIFIED_REMOTE

val RemoteS3File.cached: Boolean
    get() = verificationLevel == S3RemoteVerificationLevel.INDEX_CACHED_REMOTE

internal fun RemoteS3File.memoContentFingerprint(): String? =
    contentMd5 ?: normalizeSinglePartS3Md5(etag)

data class S3SyncAction(
    val path: String,
    val direction: S3SyncDirection,
    val reason: S3SyncReason,
)

data class S3SyncPlan(
    val actions: List<S3SyncAction>,
    val pendingChanges: Int,
)

internal fun S3SyncAction.toOutcome(): S3SyncOutcome =
    S3SyncOutcome(
        path = path,
        direction = direction,
        reason = reason,
    )
