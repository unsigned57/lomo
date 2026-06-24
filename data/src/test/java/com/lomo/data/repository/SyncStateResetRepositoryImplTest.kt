package com.lomo.data.repository

import com.lomo.data.git.GitMediaSyncMetadataEntry
import com.lomo.data.git.GitMediaSyncStateStore
import com.lomo.data.local.dao.PendingSyncConflictDao
import com.lomo.data.local.dao.PendingSyncReviewDao
import com.lomo.data.local.dao.RawS3SyncMetadataDao
import com.lomo.data.local.dao.RawWebDavSyncMetadataDao
import com.lomo.data.local.dao.S3LocalChangeJournalDao
import com.lomo.data.local.dao.S3RemoteIndexDao
import com.lomo.data.local.dao.S3RemoteShardScheduleTelemetrySnapshot
import com.lomo.data.local.dao.S3RemoteShardStateDao
import com.lomo.data.local.dao.S3SyncPlannerMetadataSnapshot
import com.lomo.data.local.dao.S3SyncProtocolStateDao
import com.lomo.data.local.dao.S3SyncRemoteMetadataSnapshot
import com.lomo.data.local.dao.SyncStateResetDao
import com.lomo.data.local.dao.WebDavLocalChangeJournalDao
import com.lomo.data.local.dao.WebDavLocalFingerprintDao
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
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncReviewSessionKind
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: SyncStateResetRepositoryImpl
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: clear workspace-scoped sync state during workspace transitions.
 *
 * Scenarios:
 * - Given WebDAV, S3, and Git media sync metadata, WebDAV local fingerprint cache entries,
 *   local journals, S3 remote index/protocol state, shard telemetry, and pending conflict/review
 *   sessions from an old workspace, when the sync-state reset hook runs, then those stores no
 *   longer expose old workspace state.
 *
 * Observable outcomes:
 * - Persisted metadata DAOs are empty, Git media metadata is empty, WebDAV local fingerprint lookup
 *   returns null for the stale path, journal stores read empty, S3 protocol/index stores are empty,
 *   and pending conflict/review reads for workspace-scoped backends return null.
 *
 * TDD proof:
 * - RED command: `./gradlew --no-daemon --no-configuration-cache --console=plain :data:testDebugUnitTest --tests 'com.lomo.data.repository.SyncStateResetRepositoryImplTest'`.
 * - RED symptom: `SyncStateResetRepositoryImpl` has no Git media state reset dependency, so the new
 *   named constructor argument does not compile before production is updated.
 *
 * Excludes:
 * - workspace generation schema migration, remote transport state, Git mirror filesystem cleanup,
 *   and RemoteSyncEngine refactoring.
 *
 * Test Change Justification:
 * - Reason category: Data layer module gained app update install persistence, migration archive staging workspace, settings preference repos, and strengthened sync conflict store contracts.
 * - Old behavior/assertion being replaced: previous data layer tests relied on older repository contracts and store implementations before these modules were restructured.
 * - Why old assertion is no longer correct: new modules introduce typed credential reads, positional memo identities, and staged migration/restore plans that change observable data behavior.
 * - Coverage preserved by: all existing repository scenarios retained; new scenarios added for install persistence, staging workspace, preference repos, and conflict store contracts.
 * - Why this is not fitting the test to the implementation: tests verify observable repository store outcomes, not internal implementation details.
 */
class SyncStateResetRepositoryImplTest : DataFunSpec() {
    init {
        test(
            "given stale provider state when workspace sync state resets " +
                "then metadata journals index and pending conflicts are cleared",
        ) {
            runTest {
                val webDavMetadataDao = ResetRawWebDavSyncMetadataDao()
                val webDavLocalFingerprintDao = ResetWebDavLocalFingerprintDao()
                val webDavJournalDao = ResetWebDavLocalChangeJournalDao()
                val s3MetadataDao = ResetRawS3SyncMetadataDao()
                val s3JournalDao = ResetS3LocalChangeJournalDao()
                val s3ProtocolStateDao = ResetS3SyncProtocolStateDao()
                val s3RemoteIndexDao = ResetS3RemoteIndexDao()
                val s3RemoteShardStateDao = ResetS3RemoteShardStateDao()
                val pendingConflictDao = ResetPendingSyncConflictDao()
                val pendingReviewDao = ResetPendingSyncReviewDao()
                val gitMediaSyncStateStore = InMemoryGitMediaSyncStateStore()
                val syncStateResetDao =
                    ResetSyncStateResetDao(
                        webDavMetadataDao = webDavMetadataDao,
                        webDavLocalFingerprintDao = webDavLocalFingerprintDao,
                        webDavJournalDao = webDavJournalDao,
                        s3MetadataDao = s3MetadataDao,
                        s3JournalDao = s3JournalDao,
                        s3ProtocolStateDao = s3ProtocolStateDao,
                        s3RemoteIndexDao = s3RemoteIndexDao,
                        s3RemoteShardStateDao = s3RemoteShardStateDao,
                        pendingConflictDao = pendingConflictDao,
                        pendingReviewDao = pendingReviewDao,
                    )
                seedProviderState(
                    webDavMetadataDao = webDavMetadataDao,
                    webDavLocalFingerprintDao = webDavLocalFingerprintDao,
                    s3MetadataDao = s3MetadataDao,
                    webDavJournalDao = webDavJournalDao,
                    s3JournalDao = s3JournalDao,
                    s3ProtocolStateDao = s3ProtocolStateDao,
                    s3RemoteIndexDao = s3RemoteIndexDao,
                    s3RemoteShardStateDao = s3RemoteShardStateDao,
                    pendingConflictDao = pendingConflictDao,
                    pendingReviewDao = pendingReviewDao,
                    gitMediaSyncStateStore = gitMediaSyncStateStore,
                )
                val repository =
                    SyncStateResetRepositoryImpl(
                        syncStateResetDao = syncStateResetDao,
                        gitMediaSyncStateStore = gitMediaSyncStateStore,
                    )

                repository.resetWorkspaceScopedSyncState()

                webDavMetadataDao.getAll(OLD_WORKSPACE_GENERATION).shouldBeEmpty()
                webDavLocalFingerprintDao.getByPath(STALE_WEBDAV_FINGERPRINT_PATH, OLD_WORKSPACE_GENERATION) shouldBe null
                webDavJournalDao.getAll(OLD_WORKSPACE_GENERATION).shouldBeEmpty()
                s3MetadataDao.getAll(OLD_WORKSPACE_GENERATION).shouldBeEmpty()
                s3JournalDao.getAll(OLD_WORKSPACE_GENERATION).shouldBeEmpty()
                s3ProtocolStateDao.getById(OLD_WORKSPACE_GENERATION) shouldBe null
                s3RemoteIndexDao.getAllRelativePaths(OLD_WORKSPACE_GENERATION).shouldBeEmpty()
                s3RemoteShardStateDao.getAll(OLD_WORKSPACE_GENERATION).shouldBeEmpty()
                gitMediaSyncStateStore.read().shouldBeEmpty()
                SyncBackendType.entries
                    .filterNot { backend -> backend == SyncBackendType.NONE }
                    .forEach { backend ->
                        pendingConflictDao.getByBackend(backend.name, OLD_WORKSPACE_GENERATION) shouldBe null
                        pendingReviewDao.getByBackend(backend.name, OLD_WORKSPACE_GENERATION) shouldBe null
                    }
            }
        }
    }
}

private suspend fun seedProviderState(
    webDavMetadataDao: RawWebDavSyncMetadataDao,
    webDavLocalFingerprintDao: WebDavLocalFingerprintDao,
    s3MetadataDao: RawS3SyncMetadataDao,
    webDavJournalDao: WebDavLocalChangeJournalDao,
    s3JournalDao: S3LocalChangeJournalDao,
    s3ProtocolStateDao: S3SyncProtocolStateDao,
    s3RemoteIndexDao: S3RemoteIndexDao,
    s3RemoteShardStateDao: S3RemoteShardStateDao,
    pendingConflictDao: PendingSyncConflictDao,
    pendingReviewDao: PendingSyncReviewDao,
    gitMediaSyncStateStore: GitMediaSyncStateStore,
) {
    webDavMetadataDao.upsertAll(
        listOf(
            WebDavSyncMetadataEntity(
                workspaceGeneration = OLD_WORKSPACE_GENERATION,
                relativePath = STALE_WEBDAV_FINGERPRINT_PATH,
                remotePath = "remote/old.md",
                etag = "webdav-etag",
                remoteLastModified = 10L,
                localLastModified = 20L,
                lastSyncedAt = 30L,
                lastResolvedDirection = WebDavSyncMetadataEntity.UNCHANGED,
                lastResolvedReason = WebDavSyncMetadataEntity.NONE,
            ),
        ),
    )
    webDavLocalFingerprintDao.upsert(
        WebDavLocalFingerprintEntity(
            workspaceGeneration = OLD_WORKSPACE_GENERATION,
            path = STALE_WEBDAV_FINGERPRINT_PATH,
            lastModified = 20L,
            size = 80L,
            fingerprint = "old-workspace-fingerprint",
        ),
    )
    s3MetadataDao.upsertAll(
        listOf(
            S3SyncMetadataEntity(
                workspaceGeneration = OLD_WORKSPACE_GENERATION,
                relativePath = "lomo/memos/old.md",
                remotePath = "remote/old.md",
                etag = "s3-etag",
                remoteLastModified = 10L,
                localLastModified = 20L,
                lastSyncedAt = 30L,
                lastResolvedDirection = S3SyncMetadataEntity.UNCHANGED,
                lastResolvedReason = S3SyncMetadataEntity.NONE,
            ),
        ),
    )
    webDavJournalDao.upsert(
        WebDavLocalChangeJournalEntity(
            workspaceGeneration = OLD_WORKSPACE_GENERATION,
            id = "MEMO:old.md",
            kind = WebDavLocalChangeKind.MEMO.name,
            filename = "old.md",
            changeType = WebDavLocalChangeType.UPSERT.name,
            updatedAt = 40L,
        ),
    )
    s3JournalDao.upsert(
        S3LocalChangeJournalEntity(
            workspaceGeneration = OLD_WORKSPACE_GENERATION,
            id = "GENERIC:lomo/memos/old.md",
            kind = S3LocalChangeKind.GENERIC.name,
            filename = "lomo/memos/old.md",
            changeType = S3LocalChangeType.UPSERT.name,
            updatedAt = 50L,
        ),
    )
    s3ProtocolStateDao.upsert(
        S3SyncProtocolStateEntity(
            workspaceGeneration = OLD_WORKSPACE_GENERATION,
            protocolVersion = S3_INCREMENTAL_PROTOCOL_VERSION,
            lastSuccessfulSyncAt = 60L,
            indexedLocalFileCount = 1,
            indexedRemoteFileCount = 1,
            localModeFingerprint = "old-workspace",
        ),
    )
    s3RemoteIndexDao.upsertAll(
        listOf(
            S3RemoteIndexEntity(
                workspaceGeneration = OLD_WORKSPACE_GENERATION,
                relativePath = "lomo/memos/old.md",
                remotePath = "remote/old.md",
                etag = "etag",
                remoteLastModified = 70L,
                size = 80L,
                lastSeenAt = 90L,
                lastVerifiedAt = 100L,
                scanBucket = "old",
            ),
        ),
    )
    s3RemoteShardStateDao.upsertAll(
        listOf(
            S3RemoteShardStateEntity(
                workspaceGeneration = OLD_WORKSPACE_GENERATION,
                bucketId = "old-bucket",
                relativePrefix = "lomo/memos",
                lastScannedAt = 120L,
                lastObjectCount = 1,
            ),
        ),
    )
    SyncBackendType.entries
        .filterNot { backend -> backend == SyncBackendType.NONE }
        .forEach { backend ->
            pendingConflictDao.upsert(
                PendingSyncConflictEntity(
                    workspaceGeneration = OLD_WORKSPACE_GENERATION,
                    backend = backend.name,
                    timestamp = 110L,
                    payloadJson = """{"files":[]}""",
                ),
            )
            pendingReviewDao.upsert(
                PendingSyncReviewEntity(
                    workspaceGeneration = OLD_WORKSPACE_GENERATION,
                    backend = backend.name,
                    reviewKind = SyncReviewSessionKind.INITIAL_IMPORT_PREVIEW.name,
                    timestamp = 115L,
                    payloadJson = """{"items":[]}""",
                ),
            )
        }
    gitMediaSyncStateStore.write(
        listOf(
            GitMediaSyncMetadataEntry(
                relativePath = "lomo/images/old.jpg",
                repoLastModified = 10L,
                localLastModified = 20L,
                lastSyncedAt = 30L,
                lastResolvedDirection = GitMediaSyncMetadataEntry.UNCHANGED,
                lastResolvedReason = GitMediaSyncMetadataEntry.NONE,
            ),
        ),
    )
}

private class ResetSyncStateResetDao(
    private val webDavMetadataDao: ResetRawWebDavSyncMetadataDao,
    private val webDavLocalFingerprintDao: ResetWebDavLocalFingerprintDao,
    private val webDavJournalDao: ResetWebDavLocalChangeJournalDao,
    private val s3MetadataDao: ResetRawS3SyncMetadataDao,
    private val s3JournalDao: ResetS3LocalChangeJournalDao,
    private val s3ProtocolStateDao: ResetS3SyncProtocolStateDao,
    private val s3RemoteIndexDao: ResetS3RemoteIndexDao,
    private val s3RemoteShardStateDao: ResetS3RemoteShardStateDao,
    private val pendingConflictDao: ResetPendingSyncConflictDao,
    private val pendingReviewDao: ResetPendingSyncReviewDao,
) : SyncStateResetDao {
    override suspend fun clearWebDavSyncMetadata() = webDavMetadataDao.clearAllEntries()

    override suspend fun clearWebDavLocalFingerprints() = webDavLocalFingerprintDao.clearAllEntries()

    override suspend fun clearWebDavLocalChangeJournal() = webDavJournalDao.clearAllEntries()

    override suspend fun clearS3SyncMetadata() = s3MetadataDao.clearAllEntries()

    override suspend fun clearS3LocalChangeJournal() = s3JournalDao.clearAllEntries()

    override suspend fun clearS3SyncProtocolState() = s3ProtocolStateDao.clearAllEntries()

    override suspend fun clearS3RemoteIndex() = s3RemoteIndexDao.clearAllEntries()

    override suspend fun clearS3RemoteShardState() = s3RemoteShardStateDao.clearAllEntries()

    override suspend fun clearPendingSyncConflicts() = pendingConflictDao.clearAllEntries()

    override suspend fun clearPendingSyncReviews() = pendingReviewDao.clearAllEntries()
}

private class ResetRawWebDavSyncMetadataDao : RawWebDavSyncMetadataDao {
    private val entities = linkedMapOf<Pair<String, String>, WebDavSyncMetadataEntity>()

    override suspend fun getAll(workspaceGeneration: String): List<WebDavSyncMetadataEntity> =
        entities.values.filter { entity -> entity.workspaceGeneration == workspaceGeneration }

    override suspend fun getByRelativePaths(
        relativePaths: List<String>,
        workspaceGeneration: String,
    ): List<WebDavSyncMetadataEntity> =
        relativePaths.mapNotNull { relativePath -> entities[workspaceGeneration to relativePath] }

    override suspend fun upsertAll(entities: List<WebDavSyncMetadataEntity>) {
        entities.forEach { entity ->
            this.entities[entity.workspaceGeneration to entity.relativePath] = entity
        }
    }

    override suspend fun deleteByRelativePath(
        relativePath: String,
        workspaceGeneration: String,
    ) {
        entities.remove(workspaceGeneration to relativePath)
    }

    override suspend fun deleteByRelativePaths(
        relativePaths: List<String>,
        workspaceGeneration: String,
    ) {
        relativePaths.forEach { relativePath -> entities.remove(workspaceGeneration to relativePath) }
    }

    override suspend fun clearAll(workspaceGeneration: String) {
        entities.entries.removeIf { (key, _) -> key.first == workspaceGeneration }
    }

    fun clearAllEntries() {
        entities.clear()
    }
}

private class ResetWebDavLocalFingerprintDao : WebDavLocalFingerprintDao {
    private val entities = linkedMapOf<Pair<String, String>, WebDavLocalFingerprintEntity>()

    override suspend fun getByPath(
        path: String,
        workspaceGeneration: String,
    ): WebDavLocalFingerprintEntity? = entities[workspaceGeneration to path]

    override suspend fun upsert(entity: WebDavLocalFingerprintEntity) {
        entities[entity.workspaceGeneration to entity.path] = entity
    }

    override suspend fun clearAll(workspaceGeneration: String) {
        entities.entries.removeIf { (key, _) -> key.first == workspaceGeneration }
    }

    fun clearAllEntries() {
        entities.clear()
    }

    override suspend fun deleteByExcludedPaths(
        paths: Collection<String>,
        workspaceGeneration: String,
    ) {
        entities.entries.removeIf { (key, entity) ->
            key.first == workspaceGeneration && entity.path !in paths
        }
    }
}

private class ResetWebDavLocalChangeJournalDao : WebDavLocalChangeJournalDao {
    private val entities = linkedMapOf<Pair<String, String>, WebDavLocalChangeJournalEntity>()

    override suspend fun getAll(workspaceGeneration: String): List<WebDavLocalChangeJournalEntity> =
        entities.values.filter { entity -> entity.workspaceGeneration == workspaceGeneration }

    override suspend fun upsert(entity: WebDavLocalChangeJournalEntity) {
        entities[entity.workspaceGeneration to entity.id] = entity
    }

    override suspend fun deleteByIds(
        ids: Collection<String>,
        workspaceGeneration: String,
    ) {
        ids.forEach { id -> entities.remove(workspaceGeneration to id) }
    }

    override suspend fun clearAll(workspaceGeneration: String) {
        entities.entries.removeIf { (key, _) -> key.first == workspaceGeneration }
    }

    fun clearAllEntries() {
        entities.clear()
    }
}

private class ResetRawS3SyncMetadataDao : RawS3SyncMetadataDao {
    private val entities = linkedMapOf<Pair<String, String>, S3SyncMetadataEntity>()

    override suspend fun getAll(workspaceGeneration: String): List<S3SyncMetadataEntity> =
        entities.values.filter { entity -> entity.workspaceGeneration == workspaceGeneration }

    override suspend fun getAllPlannerMetadataSnapshots(workspaceGeneration: String): List<S3SyncPlannerMetadataSnapshot> =
        getAll(workspaceGeneration).map { entity ->
            S3SyncPlannerMetadataSnapshot(
                relativePath = entity.relativePath,
                remotePath = entity.remotePath,
                etag = entity.etag,
                remoteLastModified = entity.remoteLastModified,
                localLastModified = entity.localLastModified,
                localSize = entity.localSize,
                remoteSize = entity.remoteSize,
                localFingerprint = entity.localFingerprint,
                lastSyncedAt = entity.lastSyncedAt,
                lastResolvedDirection = entity.lastResolvedDirection,
                lastResolvedReason = entity.lastResolvedReason,
            )
        }

    override suspend fun getAllRemoteMetadataSnapshots(workspaceGeneration: String): List<S3SyncRemoteMetadataSnapshot> =
        getAll(workspaceGeneration).map { entity ->
            S3SyncRemoteMetadataSnapshot(
                relativePath = entity.relativePath,
                remotePath = entity.remotePath,
                etag = entity.etag,
                remoteLastModified = entity.remoteLastModified,
            )
        }

    override suspend fun getByRelativePaths(
        relativePaths: List<String>,
        workspaceGeneration: String,
    ): List<S3SyncMetadataEntity> =
        relativePaths.mapNotNull { relativePath -> entities[workspaceGeneration to relativePath] }

    override suspend fun getLocalAuditPage(
        afterRelativePath: String?,
        limit: Int,
        workspaceGeneration: String,
    ): List<S3SyncMetadataEntity> =
        getAll(workspaceGeneration)
            .filter { entity -> afterRelativePath == null || entity.relativePath > afterRelativePath }
            .sortedBy(S3SyncMetadataEntity::relativePath)
            .take(limit)

    override suspend fun upsertAll(entities: List<S3SyncMetadataEntity>) {
        entities.forEach { entity ->
            this.entities[entity.workspaceGeneration to entity.relativePath] = entity
        }
    }

    override suspend fun deleteByRelativePath(
        relativePath: String,
        workspaceGeneration: String,
    ) {
        entities.remove(workspaceGeneration to relativePath)
    }

    override suspend fun deleteByRelativePaths(
        relativePaths: List<String>,
        workspaceGeneration: String,
    ) {
        relativePaths.forEach { relativePath -> entities.remove(workspaceGeneration to relativePath) }
    }

    override suspend fun clearAll(workspaceGeneration: String) {
        entities.entries.removeIf { (key, _) -> key.first == workspaceGeneration }
    }

    fun clearAllEntries() {
        entities.clear()
    }
}

private class ResetS3LocalChangeJournalDao : S3LocalChangeJournalDao {
    private val entities = linkedMapOf<Pair<String, String>, S3LocalChangeJournalEntity>()

    override suspend fun getAll(workspaceGeneration: String): List<S3LocalChangeJournalEntity> =
        entities.values.filter { entity -> entity.workspaceGeneration == workspaceGeneration }

    override suspend fun upsert(entity: S3LocalChangeJournalEntity) {
        entities[entity.workspaceGeneration to entity.id] = entity
    }

    override suspend fun deleteByIds(
        ids: Collection<String>,
        workspaceGeneration: String,
    ) {
        ids.forEach { id -> entities.remove(workspaceGeneration to id) }
    }

    override suspend fun clearAll(workspaceGeneration: String) {
        entities.entries.removeIf { (key, _) -> key.first == workspaceGeneration }
    }

    fun clearAllEntries() {
        entities.clear()
    }
}

private class ResetS3SyncProtocolStateDao : S3SyncProtocolStateDao {
    private val entities = linkedMapOf<Pair<String, Int>, S3SyncProtocolStateEntity>()

    override suspend fun getById(
        workspaceGeneration: String,
        id: Int,
    ): S3SyncProtocolStateEntity? = entities[workspaceGeneration to id]

    override suspend fun upsert(entity: S3SyncProtocolStateEntity) {
        entities[entity.workspaceGeneration to entity.id] = entity
    }

    override suspend fun clearAll(workspaceGeneration: String) {
        entities.entries.removeIf { (key, _) -> key.first == workspaceGeneration }
    }

    fun clearAllEntries() {
        entities.clear()
    }
}

private class ResetS3RemoteIndexDao : S3RemoteIndexDao {
    private val entities = linkedMapOf<Pair<String, String>, S3RemoteIndexEntity>()

    override suspend fun getAll(workspaceGeneration: String): List<S3RemoteIndexEntity> =
        entities.values.filter { entity -> entity.workspaceGeneration == workspaceGeneration }

    override suspend fun getAllRelativePaths(workspaceGeneration: String): List<String> =
        getAll(workspaceGeneration).map(S3RemoteIndexEntity::relativePath)

    override suspend fun getPresentCount(workspaceGeneration: String): Int =
        getAll(workspaceGeneration).count { entity -> !entity.missingOnLastScan }

    override suspend fun getByRelativePaths(
        relativePaths: List<String>,
        workspaceGeneration: String,
    ): List<S3RemoteIndexEntity> =
        relativePaths.mapNotNull { relativePath -> entities[workspaceGeneration to relativePath] }

    override suspend fun getByRelativePrefix(
        relativePrefix: String,
        descendantPattern: String,
        workspaceGeneration: String,
    ): List<S3RemoteIndexEntity> =
        getAll(workspaceGeneration).filter { entity ->
            entity.relativePath == relativePrefix || entity.relativePath.startsWith("$relativePrefix/")
        }

    override suspend fun getOutsideScanBuckets(
        excludedBuckets: List<String>,
        workspaceGeneration: String,
    ): List<S3RemoteIndexEntity> =
        getAll(workspaceGeneration).filterNot { entity -> entity.scanBucket in excludedBuckets }

    override suspend fun getReconcileCandidates(
        limit: Int,
        workspaceGeneration: String,
    ): List<S3RemoteIndexEntity> =
        getAll(workspaceGeneration).take(limit)

    override suspend fun upsertAll(entities: List<S3RemoteIndexEntity>) {
        entities.forEach { entity ->
            this.entities[entity.workspaceGeneration to entity.relativePath] = entity
        }
    }

    override suspend fun deleteByRelativePaths(
        relativePaths: List<String>,
        workspaceGeneration: String,
    ) {
        relativePaths.forEach { relativePath -> entities.remove(workspaceGeneration to relativePath) }
    }

    override suspend fun deleteOutsideScanEpoch(
        scanEpoch: Long,
        workspaceGeneration: String,
    ) {
        entities.entries.removeIf { (key, entity) ->
            key.first == workspaceGeneration && entity.scanEpoch != scanEpoch
        }
    }

    override suspend fun clearAll(workspaceGeneration: String) {
        entities.entries.removeIf { (key, _) -> key.first == workspaceGeneration }
    }

    fun clearAllEntries() {
        entities.clear()
    }
}

private class ResetS3RemoteShardStateDao : S3RemoteShardStateDao {
    private val entities = linkedMapOf<Pair<String, String>, S3RemoteShardStateEntity>()

    override suspend fun getAll(workspaceGeneration: String): List<S3RemoteShardStateEntity> =
        entities.values.filter { entity -> entity.workspaceGeneration == workspaceGeneration }

    override suspend fun getByBucketId(
        bucketId: String,
        workspaceGeneration: String,
    ): S3RemoteShardStateEntity? = entities[workspaceGeneration to bucketId]

    override suspend fun getByBucketIds(
        bucketIds: List<String>,
        workspaceGeneration: String,
    ): List<S3RemoteShardStateEntity> =
        bucketIds.mapNotNull { bucketId -> entities[workspaceGeneration to bucketId] }

    override suspend fun getMostSpecificAncestor(
        relativePrefix: String,
        workspaceGeneration: String,
    ): S3RemoteShardStateEntity? =
        getAll(workspaceGeneration)
            .filter { entity ->
                val candidate = entity.relativePrefix ?: return@filter false
                relativePrefix == candidate || relativePrefix.startsWith("$candidate/")
            }.maxByOrNull { entity -> entity.relativePrefix?.length ?: 0 }

    override suspend fun getScheduleTelemetry(
        workspaceGeneration: String,
        now: Long,
        recentChangeWindowMs: Long,
        uncertaintyWindowMs: Long,
        changePressureThreshold: Double,
        verificationFailureThreshold: Double,
        minUncertaintyAttempts: Int,
        minUncertaintyFailures: Int,
    ): S3RemoteShardScheduleTelemetrySnapshot =
        S3RemoteShardScheduleTelemetrySnapshot(
            shardCount = getAll(workspaceGeneration).size,
            oldestScanAt = getAll(workspaceGeneration).minOfOrNull(S3RemoteShardStateEntity::lastScannedAt),
            hasElevatedChangePressure = 0,
            hasHighVerificationUncertainty = 0,
        )

    override suspend fun upsertAll(entities: List<S3RemoteShardStateEntity>) {
        entities.forEach { entity ->
            this.entities[entity.workspaceGeneration to entity.bucketId] = entity
        }
    }

    override suspend fun clearAll(workspaceGeneration: String) {
        entities.entries.removeIf { (key, _) -> key.first == workspaceGeneration }
    }

    fun clearAllEntries() {
        entities.clear()
    }
}

private class ResetPendingSyncConflictDao : PendingSyncConflictDao {
    private val entities = linkedMapOf<Pair<String, String>, PendingSyncConflictEntity>()

    override suspend fun getByBackend(
        backend: String,
        workspaceGeneration: String,
    ): PendingSyncConflictEntity? = entities[workspaceGeneration to backend]

    override suspend fun upsert(entity: PendingSyncConflictEntity) {
        entities[entity.workspaceGeneration to entity.backend] = entity
    }

    override suspend fun deleteByBackend(
        backend: String,
        workspaceGeneration: String,
    ) {
        entities.remove(workspaceGeneration to backend)
    }

    fun clearAllEntries() {
        entities.clear()
    }
}

private class ResetPendingSyncReviewDao : PendingSyncReviewDao {
    private val entities = linkedMapOf<Pair<String, String>, PendingSyncReviewEntity>()

    override suspend fun getByBackend(
        backend: String,
        workspaceGeneration: String,
    ): PendingSyncReviewEntity? = entities[workspaceGeneration to backend]

    override suspend fun upsert(entity: PendingSyncReviewEntity) {
        entities[entity.workspaceGeneration to entity.backend] = entity
    }

    override suspend fun deleteByBackend(
        backend: String,
        workspaceGeneration: String,
    ) {
        entities.remove(workspaceGeneration to backend)
    }

    fun clearAllEntries() {
        entities.clear()
    }
}

private class InMemoryGitMediaSyncStateStore : GitMediaSyncStateStore {
    private val entries = linkedMapOf<String, GitMediaSyncMetadataEntry>()

    override suspend fun read(): Map<String, GitMediaSyncMetadataEntry> = entries.toMap()

    override suspend fun write(entries: Collection<GitMediaSyncMetadataEntry>) {
        this.entries.clear()
        entries.forEach { entry -> this.entries[entry.relativePath] = entry }
    }

    override suspend fun clear() {
        entries.clear()
    }
}

private const val OLD_WORKSPACE_GENERATION = "workspace-old"
private const val STALE_WEBDAV_FINGERPRINT_PATH = "lomo/memos/old.md"
