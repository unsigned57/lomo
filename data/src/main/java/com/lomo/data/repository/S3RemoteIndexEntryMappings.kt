package com.lomo.data.repository

internal fun com.lomo.data.s3.S3RemoteObject.toVerifiedRemoteFile(
    relativePath: String,
    encodingSupport: S3SyncEncodingSupport,
): RemoteS3File =
    RemoteS3File(
        path = relativePath,
        etag = eTag,
        lastModified = encodingSupport.resolveRemoteLastModified(metadata, lastModified),
        remotePath = key,
        verificationLevel = S3RemoteVerificationLevel.VERIFIED_REMOTE,
    )

internal fun com.lomo.data.s3.S3RemoteObject.toRemoteIndexEntry(
    relativePath: String,
    now: Long,
    scanEpoch: Long,
    scanPriority: Int = defaultScanPriority(relativePath),
): S3RemoteIndexEntry =
    S3RemoteIndexEntry(
        relativePath = relativePath,
        remotePath = key,
        etag = eTag,
        remoteLastModified = lastModified,
        size = size,
        lastSeenAt = now,
        lastVerifiedAt = now,
        scanBucket = scanBucketFor(relativePath),
        scanPriority = settledScanPriority(relativePath, scanPriority),
        dirtySuspect = false,
        missingOnLastScan = false,
        scanEpoch = scanEpoch,
    )

internal fun S3RemoteIndexEntry.toVerifiedRemoteFile(): RemoteS3File =
    RemoteS3File(
        path = relativePath,
        etag = etag,
        lastModified = remoteLastModified,
        remotePath = remotePath,
        verificationLevel = S3RemoteVerificationLevel.VERIFIED_REMOTE,
    )

internal fun S3RemoteIndexEntry.toCachedRemoteFile(): RemoteS3File =
    RemoteS3File(
        path = relativePath,
        etag = etag,
        lastModified = remoteLastModified,
        remotePath = remotePath,
        verificationLevel = S3RemoteVerificationLevel.INDEX_CACHED_REMOTE,
    )

internal fun RemoteS3File.toRemoteIndexEntry(
    now: Long,
    scanEpoch: Long,
    scanPriority: Int = defaultScanPriority(path),
): S3RemoteIndexEntry =
    S3RemoteIndexEntry(
        relativePath = path,
        remotePath = remotePath,
        etag = etag,
        remoteLastModified = lastModified,
        size = null,
        lastSeenAt = now,
        lastVerifiedAt = now,
        scanBucket = scanBucketFor(path),
        scanPriority = settledScanPriority(path, scanPriority),
        dirtySuspect = false,
        missingOnLastScan = false,
        scanEpoch = scanEpoch,
    )

internal fun S3RemoteIndexEntry.promoteForDirtyFollowUp(
    now: Long,
    scanEpoch: Long = this.scanEpoch,
): S3RemoteIndexEntry =
    copy(
        lastSeenAt = maxOf(lastSeenAt, now),
        scanPriority = promotedScanPriority(relativePath, scanPriority),
        dirtySuspect = true,
        missingOnLastScan = false,
        scanEpoch = scanEpoch,
    )

internal fun S3RemoteIndexEntry.markMissingOnScan(
    now: Long,
    scanEpoch: Long,
): S3RemoteIndexEntry =
    copy(
        lastSeenAt = maxOf(lastSeenAt, now),
        lastVerifiedAt = now,
        scanPriority = missingScanPriority(relativePath, scanPriority),
        dirtySuspect = true,
        missingOnLastScan = true,
        scanEpoch = scanEpoch,
    )

internal fun missingRemoteIndexEntry(
    relativePath: String,
    remotePath: String,
    now: Long,
    scanEpoch: Long,
    previousPriority: Int? = null,
): S3RemoteIndexEntry =
    S3RemoteIndexEntry(
        relativePath = relativePath,
        remotePath = remotePath,
        etag = null,
        remoteLastModified = null,
        size = null,
        lastSeenAt = now,
        lastVerifiedAt = now,
        scanBucket = scanBucketFor(relativePath),
        scanPriority = missingScanPriority(relativePath, previousPriority),
        dirtySuspect = true,
        missingOnLastScan = true,
        scanEpoch = scanEpoch,
    )

internal fun scanBucketFor(relativePath: String): String =
    when {
        relativePath.endsWith(S3_MEMO_SUFFIX) -> S3_SCAN_BUCKET_MEMO
        relativePath.hasExtensionIn(S3_SYNC_IMAGE_EXTENSIONS) -> S3_SCAN_BUCKET_IMAGE
        relativePath.hasExtensionIn(S3_SYNC_VOICE_EXTENSIONS) -> S3_SCAN_BUCKET_VOICE
        else -> relativePath.substringBefore('/', relativePath)
    }

internal fun defaultScanPriority(relativePath: String): Int =
    when {
        relativePath.endsWith(S3_MEMO_SUFFIX) -> S3_SCAN_PRIORITY_MEMO
        relativePath.hasExtensionIn(S3_SYNC_IMAGE_EXTENSIONS) -> S3_SCAN_PRIORITY_IMAGE
        relativePath.hasExtensionIn(S3_SYNC_VOICE_EXTENSIONS) -> S3_SCAN_PRIORITY_VOICE
        else -> S3_SCAN_PRIORITY_DEFAULT
    }

private fun String.hasExtensionIn(extensions: Set<String>): Boolean =
    substringAfterLast('.', "").lowercase() in extensions

private const val S3_SCAN_PRIORITY_MEMO = 100
private const val S3_SCAN_PRIORITY_IMAGE = 60
private const val S3_SCAN_PRIORITY_VOICE = 50
private const val S3_SCAN_PRIORITY_DEFAULT = 10
