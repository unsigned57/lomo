package com.lomo.data.repository

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal fun buildRemoteScanPlan(
    layout: com.lomo.data.sync.SyncDirectoryLayout,
    mode: S3LocalSyncMode,
    indexedRelativePaths: Collection<String> = emptyList(),
): List<S3RemoteScanShard> =
    baseRemoteScanShards(layout, mode).flatMap { shard ->
        shard.hotSubshards(indexedRelativePaths) + shard
    }.distinctBy { shard -> shard.relativePrefix ?: S3_SCAN_BUCKET_ROOT }

private fun baseRemoteScanShards(
    layout: com.lomo.data.sync.SyncDirectoryLayout,
    mode: S3LocalSyncMode,
): List<S3RemoteScanShard> =
    when (mode) {
        is S3LocalSyncMode.Legacy ->
            listOf(
                S3RemoteScanShard(S3_SCAN_BUCKET_MEMO, "$S3_ROOT/${layout.memoFolder}"),
                S3RemoteScanShard(S3_SCAN_BUCKET_IMAGE, "$S3_ROOT/${layout.imageFolder}"),
                S3RemoteScanShard(S3_SCAN_BUCKET_VOICE, "$S3_ROOT/${layout.voiceFolder}"),
            ).distinctBy(S3RemoteScanShard::relativePrefix)

        is S3LocalSyncMode.VaultRoot -> {
            val relativePrefixes =
                listOfNotNull(mode.memoRelativeDir, mode.imageRelativeDir, mode.voiceRelativeDir)
                    .map { it.trim().trim('/') }
            if (relativePrefixes.isEmpty() || relativePrefixes.any(String::isBlank)) {
                listOf(S3RemoteScanShard(S3_SCAN_BUCKET_ROOT, null))
            } else {
                listOf(
                    S3RemoteScanShard(S3_SCAN_BUCKET_MEMO, mode.memoRelativeDir),
                    S3RemoteScanShard(S3_SCAN_BUCKET_IMAGE, mode.imageRelativeDir),
                    S3RemoteScanShard(S3_SCAN_BUCKET_VOICE, mode.voiceRelativeDir),
                    // Keep a cold root fallback so vault-root sync can still discover
                    // arbitrary content directories outside the configured memo/media roots.
                    S3RemoteScanShard(S3_SCAN_BUCKET_ROOT, null),
                ).filter { !it.relativePrefix.isNullOrBlank() || it.bucketId == S3_SCAN_BUCKET_ROOT }
                    .distinctBy(S3RemoteScanShard::relativePrefix)
            }
        }
    }

private fun S3RemoteScanShard.hotSubshards(indexedRelativePaths: Collection<String>): List<S3RemoteScanShard> {
    val basePrefix = relativePrefix?.trim()?.trim('/')
    val shardPrefixes =
        indexedRelativePaths
            .asSequence()
            .mapNotNull { indexedPath ->
                if (basePrefix == null) {
                    rootShardPrefixCandidate(indexedPath)
                } else {
                    shardPrefixCandidate(indexedPath, basePrefix)
                }
            }.groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(S3_MAX_HOT_SHARDS_PER_BUCKET)
            .map(Map.Entry<String, Int>::key)
    return shardPrefixes.map { shardPrefix ->
        S3RemoteScanShard(
            bucketId = "$bucketId:$shardPrefix",
            relativePrefix = joinRelativePath(basePrefix, shardPrefix),
        )
    }
}

private fun rootShardPrefixCandidate(indexedPath: String): String? {
    val normalizedPath = sanitizeRelativePath(indexedPath) ?: return null
    if ('/' !in normalizedPath) {
        return null
    }
    return normalizedPath.substringBefore('/').takeIf(String::isNotBlank)
}

private fun shardPrefixCandidate(
    indexedPath: String,
    basePrefix: String,
): String? {
    val normalizedPath = indexedPath.trim().trim('/')
    val normalizedBase = basePrefix.trim().trim('/')
    if (normalizedPath != normalizedBase && !normalizedPath.startsWith("$normalizedBase/")) {
        return null
    }
    val suffix =
        normalizedPath
            .removePrefix(normalizedBase)
            .trimStart('/')
            .takeIf(String::isNotBlank)
            ?: return null
    val shardPrefix = suffix.first().lowercaseChar()
    return shardPrefix.takeIf { it in S3_HOT_SHARD_PREFIXES }?.toString()
}

internal fun List<S3RemoteScanShard>.findByBucketId(bucketId: String): S3RemoteScanShard? =
    firstOrNull { it.bucketId == bucketId }

internal fun List<S3RemoteScanShard>.nextAfter(current: S3RemoteScanShard): S3RemoteScanShard? {
    val currentIndex = indexOfFirst { it.bucketId == current.bucketId }
    if (currentIndex < 0) {
        return firstOrNull()
    }
    return getOrNull(currentIndex + 1)
}

internal fun S3RemoteScanShard.remotePrefix(
    config: S3ResolvedConfig,
    encodingSupport: S3SyncEncodingSupport,
): String =
    relativePrefix
        ?.trim()
        ?.trim('/')
        ?.takeIf(String::isNotBlank)
        ?.let { "${encodingSupport.remotePathFor(it, config)}/" }
        ?: encodingSupport.remoteKeyPrefix(config)

internal fun decodeRemoteScanCursor(raw: String?): StoredS3RemoteScanCursor? =
    raw?.takeIf(String::isNotBlank)?.let { value ->
        runCatching { remoteScanCursorJson.decodeFromString<StoredS3RemoteScanCursor>(value) }.getOrNull()
    }

internal fun encodeRemoteScanCursor(cursor: StoredS3RemoteScanCursor): String =
    remoteScanCursorJson.encodeToString(StoredS3RemoteScanCursor.serializer(), cursor)

internal const val S3_SCAN_BUCKET_ROOT = "root"
internal const val S3_SCAN_BUCKET_MEMO = "memo"
internal const val S3_SCAN_BUCKET_IMAGE = "images"
internal const val S3_SCAN_BUCKET_VOICE = "voice"
private const val S3_MAX_HOT_SHARDS_PER_BUCKET = 6

private val remoteScanCursorJson = Json { ignoreUnknownKeys = true }
private val S3_HOT_SHARD_PREFIXES: Set<Char> =
    buildSet {
        addAll('0'..'9')
        addAll('a'..'z')
    }

@Serializable
internal data class StoredS3RemoteScanCursor(
    val bucketId: String,
    val continuationToken: String? = null,
)

internal data class S3RemoteScanShard(
    val bucketId: String,
    val relativePrefix: String?,
)
