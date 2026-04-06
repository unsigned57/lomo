package com.lomo.data.repository

import com.lomo.data.s3.S3RemoteListPage
import com.lomo.data.s3.S3RemoteObject
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: S3 remote reconcile support
 * - Behavior focus: reconcile should derive finer hot-prefix shards from the local remote index, verify indexed files that disappear from a fully scanned shard, and keep dirty/missing index state instead of dropping it on the floor.
 * - Observable outcomes: generated scan prefixes, reconcile candidate and missing path sets, targeted head requests, and persisted dirty/missing remote-index flags.
 * - Red phase: Fails before the fix because scan planning only rotates coarse memo/images/voice shards, reconcile never re-checks indexed entries hidden from a shard listing, and remote-index updates delete missing entries instead of retaining actionable missing/dirty state.
 * - Excludes: AWS SDK transport internals, Room generated DAO SQL, WorkManager execution, and UI rendering.
 */
class S3RemoteReconcileSupportTest {
    private val layout = SyncDirectoryLayout(memoFolder = "memo", imageFolder = "images", voiceFolder = "voice", allSameDirectory = false)
    private val encodingSupport = S3SyncEncodingSupport()
    private val config =
        S3ResolvedConfig(
            endpointUrl = "https://s3.example.com",
            region = "us-east-1",
            bucket = "bucket",
            prefix = "",
            accessKeyId = "access",
            secretAccessKey = "secret",
            sessionToken = null,
            pathStyle = S3PathStyle.PATH_STYLE,
            encryptionMode = S3EncryptionMode.NONE,
            encryptionPassword = null,
        )

    @Test
    fun `buildRemoteScanPlan derives hot character shards and keeps cold fallback`() {
        val plan =
            buildRemoteScanPlan(
                layout = layout,
                mode = S3LocalSyncMode.Legacy(),
                indexedRelativePaths =
                    listOf(
                        "lomo/memo/apple.md",
                        "lomo/memo/agenda.md",
                        "lomo/images/cat.png",
                    ),
            )

        val memoHotIndex = plan.indexOfFirst { it.relativePrefix == "lomo/memo/a" }
        val memoBaseIndex = plan.indexOfFirst { it.relativePrefix == "lomo/memo" }
        val imageHotIndex = plan.indexOfFirst { it.relativePrefix == "lomo/images/c" }
        val imageBaseIndex = plan.indexOfFirst { it.relativePrefix == "lomo/images" }

        assertTrue("expected hot memo shard", memoHotIndex >= 0)
        assertTrue("expected cold memo fallback shard", memoBaseIndex >= 0)
        assertTrue("expected hot image shard", imageHotIndex >= 0)
        assertTrue("expected cold image fallback shard", imageBaseIndex >= 0)
        assertTrue("hot memo shard should run before its cold fallback", memoHotIndex < memoBaseIndex)
        assertTrue("hot image shard should run before its cold fallback", imageHotIndex < imageBaseIndex)
        assertTrue("voice fallback shard should still be present", plan.any { it.relativePrefix == "lomo/voice" })
    }

    @Test
    fun `buildRemoteScanPlan keeps root fallback for vault root content outside configured folders`() {
        val plan =
            buildRemoteScanPlan(
                layout = layout,
                mode =
                    S3LocalSyncMode.FileVaultRoot(
                        rootDir = File("/vault"),
                        memoRelativeDir = "journal",
                        imageRelativeDir = "asset",
                        voiceRelativeDir = "voice",
                        legacyRemoteCompatibility = false,
                    ),
                indexedRelativePaths =
                    listOf(
                        "Projects/note.md",
                        "pages.kanban/board.md",
                        "asset/cover.png",
                    ),
            )

        val rootHotProjectsIndex = plan.indexOfFirst { it.relativePrefix == "Projects" }
        val rootHotPagesIndex = plan.indexOfFirst { it.relativePrefix == "pages.kanban" }
        val rootFallbackIndex = plan.indexOfFirst { it.relativePrefix == null }

        assertTrue("configured memo folder shard should remain", plan.any { it.relativePrefix == "journal" })
        assertTrue("configured image folder shard should remain", plan.any { it.relativePrefix == "asset" })
        assertTrue("configured voice folder shard should remain", plan.any { it.relativePrefix == "voice" })
        assertTrue("vault root should derive a top-level hot shard for indexed generic content", rootHotProjectsIndex >= 0)
        assertTrue("vault root should derive a second top-level hot shard for indexed generic content", rootHotPagesIndex >= 0)
        assertTrue("vault root should keep a cold root fallback", rootFallbackIndex >= 0)
        assertTrue("hot root shards should run before the cold root fallback", rootHotProjectsIndex < rootFallbackIndex)
        assertTrue("hot root shards should run before the cold root fallback", rootHotPagesIndex < rootFallbackIndex)
    }

    @Test
    fun `prepareRemoteReconcile verifies indexed file missing from fully scanned hot shard`() =
        runTest {
            val path = "lomo/memo/apple.md"
            val remoteIndexStore = InMemoryS3RemoteIndexStore()
            remoteIndexStore.upsert(listOf(remoteIndexEntry(path = path, scanBucket = S3_SCAN_BUCKET_MEMO, scanPriority = 30)))

            val hotShard =
                buildRemoteScanPlan(
                    layout = layout,
                    mode = S3LocalSyncMode.Legacy(),
                    indexedRelativePaths = listOf(path),
                ).firstOrNull { it.relativePrefix == "lomo/memo/a" }
            assertNotNull(hotShard)
            val client =
                ReconcileProbeClient(
                    onList = { throw AssertionError("reconcile should use paged listing") },
                    onListPage = { prefix, continuationToken, _ ->
                        assertEquals("lomo/memo/a/", prefix)
                        assertEquals(null, continuationToken)
                        S3RemoteListPage(objects = emptyList(), nextContinuationToken = null)
                    },
                    onGetObjectMetadata = { key ->
                        assertEquals(path, key)
                        null
                    },
                )

            val prepared =
                prepareRemoteReconcile(
                    client = client,
                    layout = layout,
                    config = config,
                    mode = S3LocalSyncMode.Legacy(),
                    protocolState =
                        S3SyncProtocolState(
                            lastSuccessfulSyncAt = System.currentTimeMillis(),
                            lastFullRemoteScanAt = System.currentTimeMillis(),
                            remoteScanCursor = encodeRemoteScanCursor(StoredS3RemoteScanCursor(bucketId = hotShard!!.bucketId)),
                        ),
                    encodingSupport = encodingSupport,
                    remoteIndexStore = remoteIndexStore,
                )

            assertEquals(setOf(path), prepared.missingRemotePaths)
            assertTrue(path in prepared.candidatePaths)
            assertEquals(listOf(path), client.headKeys)
        }

    @Test
    fun `prepareRemoteReconcile records shard scan stats after listing a page`() =
        runTest {
            val remoteIndexStore = InMemoryS3RemoteIndexStore()
            val shardStateStore = InMemoryS3RemoteShardStateStore()
            val listedPath = "lomo/memo/apple.md"
            val client =
                ReconcileProbeClient(
                    onList = { throw AssertionError("reconcile should use paged listing") },
                    onListPage = { prefix, continuationToken, maxKeys ->
                        assertEquals("lomo/memo/", prefix)
                        assertEquals(null, continuationToken)
                        assertEquals(256, maxKeys)
                        S3RemoteListPage(
                            objects =
                                listOf(
                                    S3RemoteObject(
                                        key = listedPath,
                                        eTag = "etag-apple",
                                        lastModified = 88L,
                                        size = 5L,
                                        metadata = emptyMap(),
                                    ),
                                ),
                            nextContinuationToken = null,
                        )
                    },
                )

            val prepared =
                prepareRemoteReconcile(
                    client = client,
                    layout = layout,
                    config = config,
                    mode = S3LocalSyncMode.Legacy(),
                    protocolState =
                        S3SyncProtocolState(
                            lastSuccessfulSyncAt = 1L,
                            lastFullRemoteScanAt = 1L,
                        ),
                    encodingSupport = encodingSupport,
                    remoteIndexStore = remoteIndexStore,
                    shardStateStore = shardStateStore,
                )

            val shardState = requireNotNull(shardStateStore.readByBucketId(S3_SCAN_BUCKET_MEMO))
            assertTrue(listedPath in prepared.candidatePaths)
            assertEquals("lomo/memo", shardState.relativePrefix)
            assertEquals(1, shardState.lastObjectCount)
            assertTrue("shard scan timestamp should be recorded", shardState.lastScannedAt > 0L)
            assertTrue("shard scan duration should be recorded", shardState.lastDurationMs >= 0L)
            assertEquals("listed objects should count as observed changes", 1, shardState.lastChangeCount)
        }

    @Test
    fun `prepareRemoteReconcile records idle streak and verification failures for shard state`() =
        runTest {
            val path = "lomo/memo/apple.md"
            val remoteIndexStore = InMemoryS3RemoteIndexStore()
            val shardStateStore = InMemoryS3RemoteShardStateStore()
            remoteIndexStore.upsert(listOf(remoteIndexEntry(path = path, scanBucket = S3_SCAN_BUCKET_MEMO, scanPriority = 30)))
            shardStateStore.upsert(
                listOf(
                    S3RemoteShardState(
                        bucketId = "${S3_SCAN_BUCKET_MEMO}:a",
                        relativePrefix = "lomo/memo/a",
                        lastScannedAt = 10L,
                        lastObjectCount = 1,
                        lastDurationMs = 5L,
                        lastChangeCount = 0,
                        idleScanStreak = 2,
                        lastVerificationAttemptCount = 1,
                        lastVerificationFailureCount = 0,
                    ),
                ),
            )
            val hotShard =
                buildRemoteScanPlan(
                    layout = layout,
                    mode = S3LocalSyncMode.Legacy(),
                    indexedRelativePaths = listOf(path),
                ).first { it.relativePrefix == "lomo/memo/a" }
            val client =
                ReconcileProbeClient(
                    onListPage = { _, _, _ ->
                        S3RemoteListPage(objects = emptyList(), nextContinuationToken = null)
                    },
                    onGetObjectMetadata = {
                        null
                    },
                )

            val prepared =
                prepareRemoteReconcile(
                    client = client,
                    layout = layout,
                    config = config,
                    mode = S3LocalSyncMode.Legacy(),
                    protocolState =
                        S3SyncProtocolState(
                            lastSuccessfulSyncAt = 1L,
                            lastFullRemoteScanAt = 1L,
                            remoteScanCursor = encodeRemoteScanCursor(StoredS3RemoteScanCursor(bucketId = hotShard.bucketId)),
                        ),
                    encodingSupport = encodingSupport,
                    remoteIndexStore = remoteIndexStore,
                    shardStateStore = shardStateStore,
                )

            val shardState = requireNotNull(shardStateStore.readByBucketId(hotShard.bucketId))
            assertEquals(setOf(path), prepared.missingRemotePaths)
            assertEquals(0, shardState.idleScanStreak)
            assertEquals(1, shardState.lastVerificationAttemptCount)
            assertEquals(1, shardState.lastVerificationFailureCount)
        }

    @Test
    fun `applyRemoteIndexUpdates retains dirty unresolved entries and missing tombstones`() =
        runTest {
            val unresolvedPath = "lomo/memo/note.md"
            val missingPath = "lomo/memo/gone.md"
            val remoteIndexStore = InMemoryS3RemoteIndexStore()
            remoteIndexStore.upsert(
                listOf(
                    remoteIndexEntry(path = unresolvedPath, scanBucket = S3_SCAN_BUCKET_MEMO, scanPriority = 10),
                    remoteIndexEntry(path = missingPath, scanBucket = S3_SCAN_BUCKET_MEMO, scanPriority = 10),
                ),
            )

            applyRemoteIndexUpdates(
                remoteIndexStore = remoteIndexStore,
                prepared =
                    PreparedS3Sync(
                        layout = layout,
                        localFiles = emptyMap(),
                        remoteFiles =
                            mapOf(
                                unresolvedPath to
                                    RemoteS3File(
                                        path = unresolvedPath,
                                        etag = "etag-note",
                                        lastModified = 10L,
                                        remotePath = unresolvedPath,
                                        verificationLevel = S3RemoteVerificationLevel.INDEX_CACHED_REMOTE,
                                    ),
                            ),
                        metadataByPath = emptyMap(),
                        plan = S3SyncPlan(actions = emptyList(), pendingChanges = 0),
                        normalActions = emptyList(),
                        conflictSet = null,
                        completeSnapshot = false,
                        protocolState = S3SyncProtocolState(indexedRemoteFileCount = 2),
                        remoteFileCountHint = 2,
                        remoteReconcileState =
                            PreparedRemoteReconcile(
                                observedRemoteEntries = emptyMap(),
                                missingRemotePaths = setOf(missingPath),
                                nextScanCursor = null,
                                scanEpoch = 9L,
                                completedScanCycle = false,
                            ),
                    ),
                execution =
                    S3ActionExecutionResult(
                        actionOutcomes = emptyMap(),
                        failedPaths = listOf(unresolvedPath),
                        unresolvedPaths = setOf(unresolvedPath),
                        localChanged = false,
                        localFilesAfterSync = emptyMap(),
                        remoteFilesAfterSync =
                            mapOf(
                                unresolvedPath to
                                    RemoteS3File(
                                        path = unresolvedPath,
                                        etag = "etag-note",
                                        lastModified = 10L,
                                        remotePath = unresolvedPath,
                                        verificationLevel = S3RemoteVerificationLevel.INDEX_CACHED_REMOTE,
                                    ),
                            ),
                        memoRefreshPlan = S3MemoRefreshPlan.None,
                    ),
                now = 200L,
            )

            val byPath = remoteIndexStore.readAll().associateBy(S3RemoteIndexEntry::relativePath)
            val unresolved = requireNotNull(byPath[unresolvedPath])
            val missing = requireNotNull(byPath[missingPath])

            assertTrue("unresolved entry should stay dirty", unresolved.dirtySuspect)
            assertTrue("unresolved entry should be promoted for follow-up verification", unresolved.scanPriority > 10)
            assertTrue("missing entry should be retained as a tombstone", missing.missingOnLastScan)
            assertTrue("missing entry should stay hot for verification", missing.dirtySuspect)
        }

    @Test
    fun `applyRemoteIndexUpdates replaces full snapshot through upsert and diff delete without replaceAll`() =
        runTest {
            val keptPath = "lomo/memo/keep.md"
            val refreshedPath = "lomo/memo/refresh.md"
            val removedPath = "lomo/memo/remove.md"
            val remoteIndexStore =
                RecordingSnapshotRemoteIndexStore(
                    initialEntries =
                        listOf(
                            remoteIndexEntry(path = keptPath, scanBucket = S3_SCAN_BUCKET_MEMO, scanPriority = 80),
                            remoteIndexEntry(path = refreshedPath, scanBucket = S3_SCAN_BUCKET_MEMO, scanPriority = 80),
                            remoteIndexEntry(path = removedPath, scanBucket = S3_SCAN_BUCKET_MEMO, scanPriority = 80),
                        ),
                )

            applyRemoteIndexUpdates(
                remoteIndexStore = remoteIndexStore,
                prepared =
                    PreparedS3Sync(
                        layout = layout,
                        localFiles = emptyMap(),
                        remoteFiles = emptyMap(),
                        metadataByPath = emptyMap(),
                        plan = S3SyncPlan(actions = emptyList(), pendingChanges = 0),
                        normalActions = emptyList(),
                        conflictSet = null,
                        completeSnapshot = true,
                        protocolState = S3SyncProtocolState(indexedRemoteFileCount = 3, scanEpoch = 7L),
                        remoteFileCountHint = 3,
                        remoteReconcileState =
                            PreparedRemoteReconcile(
                                observedRemoteEntries = emptyMap(),
                                missingRemotePaths = emptySet(),
                                nextScanCursor = null,
                                scanEpoch = 9L,
                                completedScanCycle = true,
                            ),
                    ),
                execution =
                    S3ActionExecutionResult(
                        actionOutcomes = emptyMap(),
                        failedPaths = emptyList(),
                        unresolvedPaths = emptySet(),
                        localChanged = false,
                        localFilesAfterSync = emptyMap(),
                        remoteFilesAfterSync =
                            mapOf(
                                keptPath to
                                    RemoteS3File(
                                        path = keptPath,
                                        etag = "etag-keep",
                                        lastModified = 20L,
                                        remotePath = keptPath,
                                        verificationLevel = S3RemoteVerificationLevel.VERIFIED_REMOTE,
                                    ),
                                refreshedPath to
                                    RemoteS3File(
                                        path = refreshedPath,
                                        etag = "etag-refresh",
                                        lastModified = 30L,
                                        remotePath = refreshedPath,
                                        verificationLevel = S3RemoteVerificationLevel.VERIFIED_REMOTE,
                                    ),
                            ),
                        memoRefreshPlan = S3MemoRefreshPlan.None,
                    ),
                now = 300L,
            )

            val byPath = remoteIndexStore.readAll().associateBy(S3RemoteIndexEntry::relativePath)
            assertEquals(0, remoteIndexStore.replaceAllCalls)
            assertEquals(setOf(removedPath), remoteIndexStore.deletedPaths)
            assertEquals(setOf(keptPath, refreshedPath), remoteIndexStore.upsertedPaths)
            assertTrue("full snapshot should delete paths missing from the new scan", removedPath !in byPath)
            assertEquals(9L, byPath[keptPath]?.scanEpoch)
            assertEquals(9L, byPath[refreshedPath]?.scanEpoch)
        }

    private fun remoteIndexEntry(
        path: String,
        scanBucket: String,
        scanPriority: Int,
    ) = S3RemoteIndexEntry(
        relativePath = path,
        remotePath = path,
        etag = "etag-$path",
        remoteLastModified = 10L,
        size = 1L,
        lastSeenAt = 10L,
        lastVerifiedAt = 10L,
        scanBucket = scanBucket,
        scanPriority = scanPriority,
        dirtySuspect = false,
        missingOnLastScan = false,
        scanEpoch = 1L,
    )
}

private class ReconcileProbeClient(
    private val onList: suspend () -> List<S3RemoteObject> = { emptyList() },
    private val onListPage: suspend (String, String?, Int) -> S3RemoteListPage = { _, _, _ ->
        S3RemoteListPage(objects = emptyList(), nextContinuationToken = null)
    },
    private val onGetObjectMetadata: suspend (String) -> S3RemoteObject? = { null },
) : com.lomo.data.s3.LomoS3Client {
    val headKeys = mutableListOf<String>()

    override suspend fun verifyAccess(prefix: String) = Unit

    override suspend fun getObjectMetadata(key: String): S3RemoteObject? {
        headKeys += key
        return onGetObjectMetadata(key)
    }

    override suspend fun list(
        prefix: String,
        maxKeys: Int?,
    ): List<S3RemoteObject> = onList()

    override suspend fun listPage(
        prefix: String,
        continuationToken: String?,
        maxKeys: Int,
    ): S3RemoteListPage = onListPage(prefix, continuationToken, maxKeys)

    override suspend fun getObject(key: String): com.lomo.data.s3.S3RemoteObjectPayload {
        error("getObject should not be used in reconcile test")
    }

    override suspend fun putObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
    ): com.lomo.data.s3.S3PutObjectResult {
        error("putObject should not be used in reconcile test")
    }

    override suspend fun deleteObject(key: String) = Unit

    override fun close() = Unit
}

private class RecordingSnapshotRemoteIndexStore(
    initialEntries: List<S3RemoteIndexEntry>,
) : S3RemoteIndexStore {
    private val delegate = InMemoryS3RemoteIndexStore()
    val upsertedPaths = linkedSetOf<String>()
    val deletedPaths = linkedSetOf<String>()
    var replaceAllCalls: Int = 0
        private set

    init {
        kotlinx.coroutines.runBlocking {
            delegate.upsert(initialEntries)
        }
    }

    override val remoteIndexEnabled: Boolean
        get() = delegate.remoteIndexEnabled

    override suspend fun readAll(): List<S3RemoteIndexEntry> = delegate.readAll()

    override suspend fun readPresentCount(): Int = delegate.readPresentCount()

    override suspend fun readByRelativePaths(relativePaths: Collection<String>): List<S3RemoteIndexEntry> =
        delegate.readByRelativePaths(relativePaths)

    override suspend fun readByRelativePrefix(relativePrefix: String?): List<S3RemoteIndexEntry> =
        delegate.readByRelativePrefix(relativePrefix)

    override suspend fun readReconcileCandidates(limit: Int): List<S3RemoteIndexEntry> =
        delegate.readReconcileCandidates(limit)

    override suspend fun upsert(entries: Collection<S3RemoteIndexEntry>) {
        upsertedPaths += entries.map(S3RemoteIndexEntry::relativePath)
        delegate.upsert(entries)
    }

    override suspend fun deleteByRelativePaths(relativePaths: Collection<String>) {
        deletedPaths += relativePaths
        delegate.deleteByRelativePaths(relativePaths)
    }

    override suspend fun deleteOutsideScanEpoch(scanEpoch: Long) {
        delegate.deleteOutsideScanEpoch(scanEpoch)
    }

    override suspend fun replaceAll(entries: Collection<S3RemoteIndexEntry>) {
        replaceAllCalls += 1
        error("replaceAll should not be used for full snapshot updates")
    }

    override suspend fun clear() {
        delegate.clear()
    }
}
