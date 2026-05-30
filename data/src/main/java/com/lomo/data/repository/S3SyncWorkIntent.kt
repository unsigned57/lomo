package com.lomo.data.repository

enum class S3SyncWorkIntent {
    FAST_ONLY,
    FAST_THEN_RECONCILE,
    FULL_RECONCILE,
}

const val S3_SYNC_WORK_INTENT_PARAMETER = "s3_sync_work_intent"

interface S3SyncWorkExecutor {
    suspend fun executeS3Sync(intent: S3SyncWorkIntent): com.lomo.domain.model.S3SyncResult
}

enum class S3RemoteVerificationLevel {
    VERIFIED_REMOTE,
    INDEX_CACHED_REMOTE,
    SUSPECT_REMOTE_MISSING,
    UNKNOWN_REMOTE,
}
