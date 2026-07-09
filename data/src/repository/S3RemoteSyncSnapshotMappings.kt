package com.lomo.data.repository

import com.lomo.data.local.entity.S3SyncMetadataEntity

internal fun LocalS3File.toRemoteSyncLocalSnapshot(): RemoteSyncLocalSnapshot =
    RemoteSyncLocalSnapshot(
        path = path,
        lastModified = lastModified,
        size = size,
        localFingerprint = localFingerprint,
    )

internal fun Map<String, LocalS3File>.toS3RemoteSyncLocalSnapshots():
    Map<String, RemoteSyncLocalSnapshot> =
    mapValues { (_, file) -> file.toRemoteSyncLocalSnapshot() }

internal fun RemoteS3File.toRemoteSyncRemoteSnapshot(): RemoteSyncRemoteSnapshot =
    RemoteSyncRemoteSnapshot(
        path = path,
        etag = etag,
        lastModified = lastModified,
        size = size,
        contentFingerprint = contentMd5,
    )

internal fun Map<String, RemoteS3File>.toS3RemoteSyncRemoteSnapshots():
    Map<String, RemoteSyncRemoteSnapshot> =
    mapValues { (_, file) -> file.toRemoteSyncRemoteSnapshot() }

internal fun S3RemoteVerificationLevel.toRemoteSyncRemoteAbsenceVerification():
    RemoteSyncRemoteAbsenceVerification =
    when (this) {
        S3RemoteVerificationLevel.VERIFIED_REMOTE ->
            RemoteSyncRemoteAbsenceVerification.VERIFIED_ABSENT
        S3RemoteVerificationLevel.INDEX_CACHED_REMOTE,
        S3RemoteVerificationLevel.SUSPECT_REMOTE_MISSING,
        S3RemoteVerificationLevel.UNKNOWN_REMOTE,
        -> RemoteSyncRemoteAbsenceVerification.UNVERIFIED_ABSENT
    }

internal fun Map<String, S3RemoteVerificationLevel>.toS3RemoteSyncRemoteAbsenceVerifications():
    Map<String, RemoteSyncRemoteAbsenceVerification> =
    mapValues { (_, verificationLevel) -> verificationLevel.toRemoteSyncRemoteAbsenceVerification() }

internal fun S3SyncMetadataEntity.toRemoteSyncMetadataSnapshot(): RemoteSyncMetadataSnapshot =
    RemoteSyncMetadataSnapshot(
        path = relativePath,
        etag = etag,
        remoteLastModified = remoteLastModified,
        localLastModified = localLastModified,
        localFingerprint = localFingerprint,
        lastSyncedAt = lastSyncedAt,
    )

internal fun Map<String, S3SyncMetadataEntity>.toS3RemoteSyncMetadataSnapshots():
    Map<String, RemoteSyncMetadataSnapshot> =
    mapValues { (_, entity) -> entity.toRemoteSyncMetadataSnapshot() }
