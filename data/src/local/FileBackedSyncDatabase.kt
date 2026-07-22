package com.lomo.data.local

import android.content.Context
import com.lomo.data.local.dao.PendingSyncConflictDao
import com.lomo.data.local.dao.PendingSyncReviewDao
import com.lomo.data.local.dao.RawS3SyncMetadataDao
import com.lomo.data.local.dao.RawWebDavSyncMetadataDao
import com.lomo.data.local.dao.S3LocalChangeJournalDao
import com.lomo.data.local.dao.S3RemoteIndexDao
import com.lomo.data.local.dao.S3RemoteShardScheduleTelemetrySnapshot
import com.lomo.data.local.dao.S3RemoteShardStateDao
import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.dao.S3SyncPlannerMetadataSnapshot
import com.lomo.data.local.dao.S3SyncProtocolStateDao
import com.lomo.data.local.dao.S3SyncRemoteMetadataSnapshot
import com.lomo.data.local.dao.SyncStateResetDao
import com.lomo.data.local.dao.WebDavLocalChangeJournalDao
import com.lomo.data.local.dao.WebDavLocalFingerprintDao
import com.lomo.data.local.dao.WebDavSyncMetadataDao
import com.lomo.data.local.entity.PendingSyncConflictEntity
import com.lomo.data.local.entity.PendingSyncReviewEntity
import com.lomo.data.local.entity.S3LocalChangeJournalEntity
import com.lomo.data.local.entity.S3RemoteIndexEntity
import com.lomo.data.local.entity.S3RemoteShardStateEntity
import com.lomo.data.local.entity.S3SyncMetadataEntity
import com.lomo.data.local.entity.S3SyncProtocolStateEntity
import com.lomo.data.local.entity.WebDavLocalChangeJournalEntity
import com.lomo.data.local.entity.WebDavLocalFingerprintEntity
import com.lomo.data.local.entity.WebDavSyncMetadataEntity
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Process-local + durable file-backed sync/cache tables after Room tail deletion (P3-10).
 *
 * Kotlin never opens SQLite. Memo/query/FTS projections live in the Rust store owner only. Sync
 * metadata is clean-slate disposable across Room cutover and is rehydrated by the next successful
 * sync.
 */
class FileBackedSyncDatabase(
    private val rootDir: File,
) {
    constructor(context: Context) : this(File(context.filesDir, "lomo-sync-tables"))

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val mutex = Mutex()

    private val pendingConflicts = ConcurrentHashMap<String, PendingSyncConflictEntity>()
    private val pendingReviews = ConcurrentHashMap<String, PendingSyncReviewEntity>()
    private val s3Journal = ConcurrentHashMap<String, S3LocalChangeJournalEntity>()
    private val s3RemoteIndex = ConcurrentHashMap<String, S3RemoteIndexEntity>()
    private val s3Shards = ConcurrentHashMap<String, S3RemoteShardStateEntity>()
    private val s3Metadata = ConcurrentHashMap<String, S3SyncMetadataEntity>()
    private val s3Protocol = ConcurrentHashMap<String, S3SyncProtocolStateEntity>()
    private val webDavJournal = ConcurrentHashMap<String, WebDavLocalChangeJournalEntity>()
    private val webDavFingerprints = ConcurrentHashMap<String, WebDavLocalFingerprintEntity>()
    private val webDavMetadata = ConcurrentHashMap<String, WebDavSyncMetadataEntity>()

    init {
        rootDir.mkdirs()
        loadAll()
    }

    val pendingSyncConflictDao: PendingSyncConflictDao = PendingSyncConflictDaoImpl()
    val pendingSyncReviewDao: PendingSyncReviewDao = PendingSyncReviewDaoImpl()
    val s3LocalChangeJournalDao: S3LocalChangeJournalDao = S3LocalChangeJournalDaoImpl()
    val s3RemoteIndexDao: S3RemoteIndexDao = S3RemoteIndexDaoImpl()
    val s3RemoteShardStateDao: S3RemoteShardStateDao = S3RemoteShardStateDaoImpl()
    val rawS3SyncMetadataDao: RawS3SyncMetadataDao = RawS3SyncMetadataDaoImpl()
    val s3SyncProtocolStateDao: S3SyncProtocolStateDao = S3SyncProtocolStateDaoImpl()
    val webDavLocalChangeJournalDao: WebDavLocalChangeJournalDao = WebDavLocalChangeJournalDaoImpl()
    val webDavLocalFingerprintDao: WebDavLocalFingerprintDao = WebDavLocalFingerprintDaoImpl()
    val rawWebDavSyncMetadataDao: RawWebDavSyncMetadataDao = RawWebDavSyncMetadataDaoImpl()
    val syncStateResetDao: SyncStateResetDao = SyncStateResetDaoImpl()

    suspend fun <T> runInTransaction(block: suspend () -> T): T = mutex.withLock { block() }

    private fun key2(
        a: String,
        b: String,
    ): String = "$a\u0000$b"

    private fun loadAll() {
        loadList<PendingSyncConflictEntity>("pending_conflicts.json") {
            pendingConflicts[key2(it.workspaceGeneration, it.backend)] = it
        }
        loadList<PendingSyncReviewEntity>("pending_reviews.json") {
            pendingReviews[key2(it.workspaceGeneration, it.backend)] = it
        }
        loadList<S3LocalChangeJournalEntity>("s3_journal.json") {
            s3Journal[key2(it.workspaceGeneration, it.id)] = it
        }
        loadList<S3RemoteIndexEntity>("s3_remote_index.json") {
            s3RemoteIndex[key2(it.workspaceGeneration, it.relativePath)] = it
        }
        loadList<S3RemoteShardStateEntity>("s3_shards.json") {
            s3Shards[key2(it.workspaceGeneration, it.bucketId)] = it
        }
        loadList<S3SyncMetadataEntity>("s3_metadata.json") {
            s3Metadata[key2(it.workspaceGeneration, it.relativePath)] = it
        }
        loadList<S3SyncProtocolStateEntity>("s3_protocol.json") {
            s3Protocol[key2(it.workspaceGeneration, it.id.toString())] = it
        }
        loadList<WebDavLocalChangeJournalEntity>("webdav_journal.json") {
            webDavJournal[key2(it.workspaceGeneration, it.id)] = it
        }
        loadList<WebDavLocalFingerprintEntity>("webdav_fingerprints.json") {
            webDavFingerprints[key2(it.workspaceGeneration, it.path)] = it
        }
        loadList<WebDavSyncMetadataEntity>("webdav_metadata.json") {
            webDavMetadata[key2(it.workspaceGeneration, it.relativePath)] = it
        }
    }

    private inline fun <reified T> loadList(
        name: String,
        put: (T) -> Unit,
    ) {
        val file = File(rootDir, name)
        if (!file.isFile) return
        // behavior-contract: silent-result-ok: corrupt sync table file is clean-slate discarded
        runCatching {
            json.decodeFromString<ListEnvelope<T>>(file.readText()).items.forEach(put)
        }
    }

    private inline fun <reified T> persist(
        name: String,
        items: Collection<T>,
    ) {
        val file = File(rootDir, name)
        val tmp = File(rootDir, "$name.tmp")
        tmp.writeText(json.encodeToString(ListEnvelope(items = items.toList())))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    @Serializable
    private data class ListEnvelope<T>(
        val items: List<T>,
    )

    private inner class PendingSyncConflictDaoImpl : PendingSyncConflictDao {
        override suspend fun getByBackend(
            backend: String,
            workspaceGeneration: String,
        ): PendingSyncConflictEntity? = pendingConflicts[key2(workspaceGeneration, backend)]

        override suspend fun upsert(entity: PendingSyncConflictEntity) {
            pendingConflicts[key2(entity.workspaceGeneration, entity.backend)] = entity
            persist("pending_conflicts.json", pendingConflicts.values)
        }

        override suspend fun deleteByBackend(
            backend: String,
            workspaceGeneration: String,
        ) {
            pendingConflicts.remove(key2(workspaceGeneration, backend))
            persist("pending_conflicts.json", pendingConflicts.values)
        }
    }

    private inner class PendingSyncReviewDaoImpl : PendingSyncReviewDao {
        override suspend fun getByBackend(
            backend: String,
            workspaceGeneration: String,
        ): PendingSyncReviewEntity? = pendingReviews[key2(workspaceGeneration, backend)]

        override suspend fun upsert(entity: PendingSyncReviewEntity) {
            pendingReviews[key2(entity.workspaceGeneration, entity.backend)] = entity
            persist("pending_reviews.json", pendingReviews.values)
        }

        override suspend fun deleteByBackend(
            backend: String,
            workspaceGeneration: String,
        ) {
            pendingReviews.remove(key2(workspaceGeneration, backend))
            persist("pending_reviews.json", pendingReviews.values)
        }
    }

    private inner class S3LocalChangeJournalDaoImpl : S3LocalChangeJournalDao {
        override suspend fun getAll(workspaceGeneration: String): List<S3LocalChangeJournalEntity> =
            s3Journal.values.filter { it.workspaceGeneration == workspaceGeneration }.sortedBy { it.id }

        override suspend fun upsert(entity: S3LocalChangeJournalEntity) {
            s3Journal[key2(entity.workspaceGeneration, entity.id)] = entity
            persist("s3_journal.json", s3Journal.values)
        }

        override suspend fun deleteByIds(
            ids: Collection<String>,
            workspaceGeneration: String,
        ) {
            ids.forEach { s3Journal.remove(key2(workspaceGeneration, it)) }
            persist("s3_journal.json", s3Journal.values)
        }

        override suspend fun clearAll(workspaceGeneration: String) {
            s3Journal.keys.filter { it.startsWith("$workspaceGeneration\u0000") }.forEach { s3Journal.remove(it) }
            persist("s3_journal.json", s3Journal.values)
        }
    }

    private inner class S3RemoteIndexDaoImpl : S3RemoteIndexDao {
        private fun gen(workspaceGeneration: String) =
            s3RemoteIndex.values.filter { it.workspaceGeneration == workspaceGeneration }

        override suspend fun getAll(workspaceGeneration: String) = gen(workspaceGeneration)

        override suspend fun getAllRelativePaths(workspaceGeneration: String) =
            gen(workspaceGeneration).map { it.relativePath }

        override suspend fun getPresentCount(workspaceGeneration: String) =
            gen(workspaceGeneration).count { !it.missingOnLastScan }

        override suspend fun getByRelativePaths(
            relativePaths: List<String>,
            workspaceGeneration: String,
        ) = gen(workspaceGeneration).filter { it.relativePath in relativePaths.toSet() }

        override suspend fun getByRelativePrefix(
            relativePrefix: String,
            descendantPattern: String,
            workspaceGeneration: String,
        ): List<S3RemoteIndexEntity> {
            val prefix = descendantPattern.removeSuffix("%")
            return gen(workspaceGeneration).filter {
                it.relativePath == relativePrefix || it.relativePath.startsWith(prefix)
            }
        }

        override suspend fun getOutsideScanBuckets(
            excludedBuckets: List<String>,
            workspaceGeneration: String,
        ) = gen(workspaceGeneration).filter { it.scanBucket !in excludedBuckets.toSet() }

        override suspend fun getReconcileCandidates(
            limit: Int,
            workspaceGeneration: String,
        ) = gen(workspaceGeneration)
            .sortedWith(
                compareByDescending<S3RemoteIndexEntity> { it.dirtySuspect }
                    .thenByDescending { it.missingOnLastScan }
                    .thenByDescending { it.scanPriority }
                    .thenBy { it.lastVerifiedAt ?: 0L }
                    .thenBy { it.lastSeenAt },
            ).take(limit)

        override suspend fun upsertAll(entities: List<S3RemoteIndexEntity>) {
            entities.forEach { s3RemoteIndex[key2(it.workspaceGeneration, it.relativePath)] = it }
            persist("s3_remote_index.json", s3RemoteIndex.values)
        }

        override suspend fun deleteByRelativePaths(
            relativePaths: List<String>,
            workspaceGeneration: String,
        ) {
            relativePaths.forEach { s3RemoteIndex.remove(key2(workspaceGeneration, it)) }
            persist("s3_remote_index.json", s3RemoteIndex.values)
        }

        override suspend fun deleteOutsideScanEpoch(
            scanEpoch: Long,
            workspaceGeneration: String,
        ) {
            gen(workspaceGeneration)
                .filter { it.scanEpoch != scanEpoch }
                .forEach { s3RemoteIndex.remove(key2(workspaceGeneration, it.relativePath)) }
            persist("s3_remote_index.json", s3RemoteIndex.values)
        }

        override suspend fun clearAll(workspaceGeneration: String) {
            gen(workspaceGeneration).forEach { s3RemoteIndex.remove(key2(workspaceGeneration, it.relativePath)) }
            persist("s3_remote_index.json", s3RemoteIndex.values)
        }
    }

    private inner class S3RemoteShardStateDaoImpl : S3RemoteShardStateDao {
        private fun gen(workspaceGeneration: String) =
            s3Shards.values.filter { it.workspaceGeneration == workspaceGeneration }

        override suspend fun getAll(workspaceGeneration: String) = gen(workspaceGeneration)

        override suspend fun getByBucketId(
            bucketId: String,
            workspaceGeneration: String,
        ) = s3Shards[key2(workspaceGeneration, bucketId)]

        override suspend fun getByBucketIds(
            bucketIds: List<String>,
            workspaceGeneration: String,
        ) = gen(workspaceGeneration).filter { it.bucketId in bucketIds.toSet() }

        override suspend fun getMostSpecificAncestor(
            relativePrefix: String,
            workspaceGeneration: String,
        ): S3RemoteShardStateEntity? =
            gen(workspaceGeneration)
                .filter { shard ->
                    val prefix = shard.relativePrefix ?: return@filter false
                    relativePrefix == prefix || relativePrefix.startsWith("$prefix/")
                }.maxByOrNull { it.relativePrefix?.length ?: 0 }

        override suspend fun getScheduleTelemetry(
            workspaceGeneration: String,
            now: Long,
            recentChangeWindowMs: Long,
            uncertaintyWindowMs: Long,
            changePressureThreshold: Double,
            verificationFailureThreshold: Double,
            minUncertaintyAttempts: Int,
            minUncertaintyFailures: Int,
        ): S3RemoteShardScheduleTelemetrySnapshot {
            val shards = gen(workspaceGeneration)
            val oldest = shards.minOfOrNull { it.lastScannedAt }
            val elevated =
                shards.count { shard ->
                    val pressure =
                        if (shard.lastObjectCount <= 0) {
                            0.0
                        } else {
                            shard.lastChangeCount.toDouble() / shard.lastObjectCount.toDouble()
                        }
                    pressure >= changePressureThreshold && now - shard.lastScannedAt <= recentChangeWindowMs
                }
            val uncertain =
                shards.count { shard ->
                    shard.lastVerificationAttemptCount >= minUncertaintyAttempts &&
                        shard.lastVerificationFailureCount >= minUncertaintyFailures &&
                        now - shard.lastScannedAt <= uncertaintyWindowMs &&
                        shard.lastVerificationAttemptCount > 0 &&
                        shard.lastVerificationFailureCount.toDouble() /
                            shard.lastVerificationAttemptCount.toDouble() >= verificationFailureThreshold
                }
            return S3RemoteShardScheduleTelemetrySnapshot(
                shardCount = shards.size,
                oldestScanAt = oldest,
                hasElevatedChangePressure = if (elevated > 0) 1 else 0,
                hasHighVerificationUncertainty = if (uncertain > 0) 1 else 0,
            )
        }

        override suspend fun upsertAll(entities: List<S3RemoteShardStateEntity>) {
            entities.forEach { s3Shards[key2(it.workspaceGeneration, it.bucketId)] = it }
            persist("s3_shards.json", s3Shards.values)
        }

        override suspend fun clearAll(workspaceGeneration: String) {
            gen(workspaceGeneration).forEach { s3Shards.remove(key2(workspaceGeneration, it.bucketId)) }
            persist("s3_shards.json", s3Shards.values)
        }
    }

    private inner class RawS3SyncMetadataDaoImpl : RawS3SyncMetadataDao {
        private fun gen(workspaceGeneration: String) =
            s3Metadata.values.filter { it.workspaceGeneration == workspaceGeneration }

        override suspend fun getAll(workspaceGeneration: String) = gen(workspaceGeneration)

        override suspend fun getAllPlannerMetadataSnapshots(
            workspaceGeneration: String,
        ): List<S3SyncPlannerMetadataSnapshot> =
            gen(workspaceGeneration).map {
                S3SyncPlannerMetadataSnapshot(
                    relativePath = it.relativePath,
                    remotePath = it.remotePath,
                    etag = it.etag,
                    remoteLastModified = it.remoteLastModified,
                    localLastModified = it.localLastModified,
                    localSize = it.localSize,
                    remoteSize = it.remoteSize,
                    localFingerprint = it.localFingerprint,
                    lastSyncedAt = it.lastSyncedAt,
                    lastResolvedDirection = it.lastResolvedDirection,
                    lastResolvedReason = it.lastResolvedReason,
                )
            }

        override suspend fun getAllRemoteMetadataSnapshots(
            workspaceGeneration: String,
        ): List<S3SyncRemoteMetadataSnapshot> =
            gen(workspaceGeneration).map {
                S3SyncRemoteMetadataSnapshot(
                    relativePath = it.relativePath,
                    remotePath = it.remotePath,
                    etag = it.etag,
                    remoteLastModified = it.remoteLastModified,
                )
            }

        override suspend fun getByRelativePaths(
            relativePaths: List<String>,
            workspaceGeneration: String,
        ) = gen(workspaceGeneration).filter { it.relativePath in relativePaths.toSet() }

        override suspend fun getLocalAuditPage(
            afterRelativePath: String?,
            limit: Int,
            workspaceGeneration: String,
        ) = gen(workspaceGeneration)
            .filter { afterRelativePath == null || it.relativePath > afterRelativePath }
            .sortedBy { it.relativePath }
            .take(limit)

        override suspend fun upsertAll(entities: List<S3SyncMetadataEntity>) {
            entities.forEach { s3Metadata[key2(it.workspaceGeneration, it.relativePath)] = it }
            persist("s3_metadata.json", s3Metadata.values)
        }

        override suspend fun deleteByRelativePath(
            relativePath: String,
            workspaceGeneration: String,
        ) {
            s3Metadata.remove(key2(workspaceGeneration, relativePath))
            persist("s3_metadata.json", s3Metadata.values)
        }

        override suspend fun deleteByRelativePaths(
            relativePaths: List<String>,
            workspaceGeneration: String,
        ) {
            relativePaths.forEach { s3Metadata.remove(key2(workspaceGeneration, it)) }
            persist("s3_metadata.json", s3Metadata.values)
        }

        override suspend fun clearAll(workspaceGeneration: String) {
            gen(workspaceGeneration).forEach { s3Metadata.remove(key2(workspaceGeneration, it.relativePath)) }
            persist("s3_metadata.json", s3Metadata.values)
        }
    }

    private inner class S3SyncProtocolStateDaoImpl : S3SyncProtocolStateDao {
        override suspend fun getById(
            workspaceGeneration: String,
            id: Int,
        ): S3SyncProtocolStateEntity? = s3Protocol[key2(workspaceGeneration, id.toString())]

        override suspend fun upsert(entity: S3SyncProtocolStateEntity) {
            s3Protocol[key2(entity.workspaceGeneration, entity.id.toString())] = entity
            persist("s3_protocol.json", s3Protocol.values)
        }

        override suspend fun clearAll(workspaceGeneration: String) {
            s3Protocol.keys.filter { it.startsWith("$workspaceGeneration\u0000") }.forEach { s3Protocol.remove(it) }
            persist("s3_protocol.json", s3Protocol.values)
        }
    }

    private inner class WebDavLocalChangeJournalDaoImpl : WebDavLocalChangeJournalDao {
        override suspend fun getAll(workspaceGeneration: String) =
            webDavJournal.values.filter { it.workspaceGeneration == workspaceGeneration }.sortedBy { it.id }

        override suspend fun upsert(entity: WebDavLocalChangeJournalEntity) {
            webDavJournal[key2(entity.workspaceGeneration, entity.id)] = entity
            persist("webdav_journal.json", webDavJournal.values)
        }

        override suspend fun deleteByIds(
            ids: Collection<String>,
            workspaceGeneration: String,
        ) {
            ids.forEach { webDavJournal.remove(key2(workspaceGeneration, it)) }
            persist("webdav_journal.json", webDavJournal.values)
        }

        override suspend fun clearAll(workspaceGeneration: String) {
            webDavJournal.keys
                .filter { it.startsWith("$workspaceGeneration\u0000") }
                .forEach { webDavJournal.remove(it) }
            persist("webdav_journal.json", webDavJournal.values)
        }
    }

    private inner class WebDavLocalFingerprintDaoImpl : WebDavLocalFingerprintDao {
        override suspend fun getByPath(
            path: String,
            workspaceGeneration: String,
        ) = webDavFingerprints[key2(workspaceGeneration, path)]

        override suspend fun upsert(entity: WebDavLocalFingerprintEntity) {
            webDavFingerprints[key2(entity.workspaceGeneration, entity.path)] = entity
            persist("webdav_fingerprints.json", webDavFingerprints.values)
        }

        override suspend fun clearAll(workspaceGeneration: String) {
            webDavFingerprints.keys
                .filter { it.startsWith("$workspaceGeneration\u0000") }
                .forEach { webDavFingerprints.remove(it) }
            persist("webdav_fingerprints.json", webDavFingerprints.values)
        }

        override suspend fun deleteByExcludedPaths(
            paths: Collection<String>,
            workspaceGeneration: String,
        ) {
            val keep = paths.toSet()
            webDavFingerprints.values
                .filter { it.workspaceGeneration == workspaceGeneration && it.path !in keep }
                .forEach { webDavFingerprints.remove(key2(workspaceGeneration, it.path)) }
            persist("webdav_fingerprints.json", webDavFingerprints.values)
        }
    }

    private inner class RawWebDavSyncMetadataDaoImpl : RawWebDavSyncMetadataDao {
        private fun gen(workspaceGeneration: String) =
            webDavMetadata.values.filter { it.workspaceGeneration == workspaceGeneration }

        override suspend fun getAll(workspaceGeneration: String) = gen(workspaceGeneration)

        override suspend fun getByRelativePaths(
            relativePaths: List<String>,
            workspaceGeneration: String,
        ) = gen(workspaceGeneration).filter { it.relativePath in relativePaths.toSet() }

        override suspend fun upsertAll(entities: List<WebDavSyncMetadataEntity>) {
            entities.forEach { webDavMetadata[key2(it.workspaceGeneration, it.relativePath)] = it }
            persist("webdav_metadata.json", webDavMetadata.values)
        }

        override suspend fun deleteByRelativePath(
            relativePath: String,
            workspaceGeneration: String,
        ) {
            webDavMetadata.remove(key2(workspaceGeneration, relativePath))
            persist("webdav_metadata.json", webDavMetadata.values)
        }

        override suspend fun deleteByRelativePaths(
            relativePaths: List<String>,
            workspaceGeneration: String,
        ) {
            relativePaths.forEach { webDavMetadata.remove(key2(workspaceGeneration, it)) }
            persist("webdav_metadata.json", webDavMetadata.values)
        }

        override suspend fun clearAll(workspaceGeneration: String) {
            gen(workspaceGeneration).forEach { webDavMetadata.remove(key2(workspaceGeneration, it.relativePath)) }
            persist("webdav_metadata.json", webDavMetadata.values)
        }
    }

    private inner class SyncStateResetDaoImpl : SyncStateResetDao {
        override suspend fun clearWebDavSyncMetadata() {
            webDavMetadata.clear()
            persist("webdav_metadata.json", emptyList<WebDavSyncMetadataEntity>())
        }

        override suspend fun clearWebDavLocalFingerprints() {
            webDavFingerprints.clear()
            persist("webdav_fingerprints.json", emptyList<WebDavLocalFingerprintEntity>())
        }

        override suspend fun clearWebDavLocalChangeJournal() {
            webDavJournal.clear()
            persist("webdav_journal.json", emptyList<WebDavLocalChangeJournalEntity>())
        }

        override suspend fun clearS3SyncMetadata() {
            s3Metadata.clear()
            persist("s3_metadata.json", emptyList<S3SyncMetadataEntity>())
        }

        override suspend fun clearS3LocalChangeJournal() {
            s3Journal.clear()
            persist("s3_journal.json", emptyList<S3LocalChangeJournalEntity>())
        }

        override suspend fun clearS3SyncProtocolState() {
            s3Protocol.clear()
            persist("s3_protocol.json", emptyList<S3SyncProtocolStateEntity>())
        }

        override suspend fun clearS3RemoteIndex() {
            s3RemoteIndex.clear()
            persist("s3_remote_index.json", emptyList<S3RemoteIndexEntity>())
        }

        override suspend fun clearS3RemoteShardState() {
            s3Shards.clear()
            persist("s3_shards.json", emptyList<S3RemoteShardStateEntity>())
        }

        override suspend fun clearPendingSyncConflicts() {
            pendingConflicts.clear()
            persist("pending_conflicts.json", emptyList<PendingSyncConflictEntity>())
        }

        override suspend fun clearPendingSyncReviews() {
            pendingReviews.clear()
            persist("pending_reviews.json", emptyList<PendingSyncReviewEntity>())
        }
    }
}
