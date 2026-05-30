package com.lomo.data.repository

import com.lomo.data.local.entity.WebDavSyncMetadataEntity

internal fun LocalWebDavFile.toRemoteSyncLocalSnapshot(): RemoteSyncLocalSnapshot =
    RemoteSyncLocalSnapshot(
        path = path,
        lastModified = lastModified,
        size = size,
        localFingerprint = localFingerprint,
    )

internal fun Map<String, LocalWebDavFile>.toWebDavRemoteSyncLocalSnapshots():
    Map<String, RemoteSyncLocalSnapshot> =
    mapValues { (_, file) -> file.toRemoteSyncLocalSnapshot() }

internal fun RemoteWebDavFile.toRemoteSyncRemoteSnapshot(): RemoteSyncRemoteSnapshot =
    RemoteSyncRemoteSnapshot(
        path = path,
        etag = etag,
        lastModified = lastModified,
        size = size,
    )

internal fun Map<String, RemoteWebDavFile>.toWebDavRemoteSyncRemoteSnapshots():
    Map<String, RemoteSyncRemoteSnapshot> =
    mapValues { (_, file) -> file.toRemoteSyncRemoteSnapshot() }

internal fun WebDavSyncMetadataEntity.toRemoteSyncMetadataSnapshot(): RemoteSyncMetadataSnapshot =
    RemoteSyncMetadataSnapshot(
        path = relativePath,
        etag = etag,
        remoteLastModified = remoteLastModified,
        localLastModified = localLastModified,
        localFingerprint = localFingerprint,
        lastSyncedAt = lastSyncedAt,
    )

internal fun Map<String, WebDavSyncMetadataEntity>.toWebDavRemoteSyncMetadataSnapshots():
    Map<String, RemoteSyncMetadataSnapshot> =
    mapValues { (_, entity) -> entity.toRemoteSyncMetadataSnapshot() }
