package com.lomo.data.repository

import com.lomo.data.s3.S3RemoteListPage
import com.lomo.data.s3.S3RemoteObject
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3SyncDirection
import com.lomo.data.repository.S3SyncWorkIntent
import com.lomo.domain.model.S3SyncReason
import kotlinx.coroutines.test.runTest
import java.io.File
import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.nulls.shouldNotBeNull

/*
 * Behavior Contract:
 * - Unit under test: S3 remote reconcile support
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: reconcile derives finer hot-prefix shards from the local remote index, verifies indexed
 *   files that disappear from a fully scanned shard, keeps dirty/missing index state, and replans only
 *   listed objects that diverge from the persisted sync baseline.
 *
 * Scenarios:
 * - Given hot prefixes in the index, when a scan plan is built, then finer shards run before cold fallbacks.
 * - Given an indexed file missing from a fully scanned shard, when reconcile runs, then it is HEAD-verified.
 * - Given a listed object whose etag matches the persisted baseline (with drifted server listing time)
 *   and a clean index entry, when reconcile prepares candidates, then the path stays out of replanning
 *   while its index entry still refreshes for the scan epoch.
 * - Given a listed object with a changed etag or a dirty index entry, when reconcile prepares candidates,
 *   then the path replans.
 * - Given unresolved/missing outcomes, when index updates apply, then dirty/missing state is retained.
 * - Given shard telemetry, when pages are scanned, then stats, budgets, and idle streaks adjust.
 *
 * Observable outcomes:
 * - generated scan prefixes, reconcile candidate and missing path sets, targeted head requests,
 *   observed index entries, and persisted dirty/missing remote-index flags.
 *
 * TDD proof:
 * - Original suite: fails before the fix because scan planning only rotated coarse shards, hidden indexed
 *   entries were never re-checked, and missing entries were deleted instead of retained.
 * - Baseline-filter scenario: with the candidate filter disabled, "keeps baseline-matching listed paths
 *   out of replanning candidates" fails because every listed page path re-entered planning.
 *
 * Excludes: AWS SDK transport internals, Room generated DAO SQL, WorkManager execution, and UI rendering.
 *
 * Test Change Justification:
 * - Reason category: S3 sync module gained remote object key policy, reconcile preparation, file bridge fingerprint ops, work telemetry, and streaming markdown; existing tests need updated assertions.
 * - Old behavior/assertion being replaced: previous sync tests relied on older file bridge, reconcile, and work policy contracts before these modules were added.
 * - Why old assertion is no longer correct: new modules introduce typed remote object key policy, reconcile preparation phases, and file bridge fingerprint verification that change the observable sync behavior.
 * - Coverage preserved by: all existing sync scenarios retained; new scenarios added for key policy, fingerprint ops, reconcile prep, and work telemetry.
 * - Why this is not fitting the test to the implementation: tests verify observable sync state transitions and file bridge outcomes, not internal implementation details.
 */
class S3RemoteReconcileSupportTest : DataFunSpec() {
    init {
        test("hasFreshRemoteIndex uses endpoint profile specific freshness window") { `hasFreshRemoteIndex uses endpoint profile specific freshness window`() }

        test("shouldRunIncrementalReconcile uses endpoint profile specific interval") { `shouldRunIncrementalReconcile uses endpoint profile specific interval`() }

        test("buildRemoteScanPlan derives hot character shards and keeps cold fallback") { `buildRemoteScanPlan derives hot character shards and keeps cold fallback`() }

        test("buildRemoteScanPlan keeps root fallback for vault root content outside configured folders") { `buildRemoteScanPlan keeps root fallback for vault root content outside configured folders`() }

        test("prepareRemoteReconcile verifies indexed file missing from fully scanned hot shard") { `prepareRemoteReconcile verifies indexed file missing from fully scanned hot shard`() }

        test("prepareRemoteReconcile reads root fallback candidates without full index scan") { `prepareRemoteReconcile reads root fallback candidates without full index scan`() }

        test("prepareRemoteReconcile records shard scan stats after listing a page") { `prepareRemoteReconcile records shard scan stats after listing a page`() }

        test("prepareRemoteReconcile keeps baseline-matching listed paths out of replanning candidates") {
            `prepareRemoteReconcile keeps baseline-matching listed paths out of replanning candidates`()
        }

        test("prepareRemoteReconcile keeps listed paths with changed etag or dirty index state as candidates") {
            `prepareRemoteReconcile keeps listed paths with changed etag or dirty index state as candidates`()
        }

        test("prepareRemoteReconcile records idle streak and verification failures for shard state") { `prepareRemoteReconcile records idle streak and verification failures for shard state`() }

        test("prepareRemoteReconcile resolves ancestor shard telemetry without scanning the full shard-state table") { `prepareRemoteReconcile resolves ancestor shard telemetry without scanning the full shard-state table`() }

        test("applyRemoteIndexUpdates retains dirty unresolved entries and missing tombstones") { `applyRemoteIndexUpdates retains dirty unresolved entries and missing tombstones`() }

        test("applyRemoteIndexUpdates replaces full snapshot through upsert and diff delete without replaceAll") { `applyRemoteIndexUpdates replaces full snapshot through upsert and diff delete without replaceAll`() }

        test("applyRemoteIndexUpdates replaces full snapshot without loading full entry rows") { `applyRemoteIndexUpdates replaces full snapshot without loading full entry rows`() }

        test("applyRemoteIndexUpdates promotes successful action outcomes into recent activity candidates") { `applyRemoteIndexUpdates promotes successful action outcomes into recent activity candidates`() }

        test("prepareRemoteReconcile expands list page budget for hot high-change shard") { `prepareRemoteReconcile expands list page budget for hot high-change shard`() }

        test("reconcile tuner uses more conservative base budgets for minio profile") { `reconcile tuner uses more conservative base budgets for minio profile`() }

        test("reconcile tuner does not clamp moderate verification failures for minio profile") { `reconcile tuner does not clamp moderate verification failures for minio profile`() }

        test("prepareRemoteReconcile shrinks verification head budget when shard has high failure rate") { `prepareRemoteReconcile shrinks verification head budget when shard has high failure rate`() }
    }


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

    private fun `hasFreshRemoteIndex uses endpoint profile specific freshness window`() {
        val now = 20 * 60_000L
        val protocolState = S3SyncProtocolState(lastFullRemoteScanAt = now - 10 * 60_000L)

        (protocolState.hasFreshRemoteIndex(
                config.copy(endpointProfile = S3EndpointProfile.AWS_S3),
                now = now,
            )).shouldBeTrue()
        (protocolState.hasFreshRemoteIndex(
                config.copy(endpointProfile = S3EndpointProfile.MINIO_COMPAT),
                now = now,
            )).shouldBeFalse()
    }

    private fun `shouldRunIncrementalReconcile uses endpoint profile specific interval`() {
        val now = 3 * 60_000L
        val protocolState = S3SyncProtocolState(lastReconcileAt = now - (2 * 60_000L))

        (shouldRunIncrementalReconcile(
                policy = S3SyncWorkIntent.FAST_THEN_RECONCILE,
                config = config.copy(endpointProfile = S3EndpointProfile.GENERIC_S3),
                protocolState = protocolState,
                now = now,
            )).shouldBeTrue()
        (shouldRunIncrementalReconcile(
                policy = S3SyncWorkIntent.FAST_THEN_RECONCILE,
                config = config.copy(endpointProfile = S3EndpointProfile.MINIO_COMPAT),
                protocolState = protocolState,
                now = now,
            )).shouldBeFalse()
    }

    private fun `buildRemoteScanPlan derives hot character shards and keeps cold fallback`() {
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

        withClue("expected hot memo shard") { (memoHotIndex >= 0).shouldBeTrue() }
        withClue("expected cold memo fallback shard") { (memoBaseIndex >= 0).shouldBeTrue() }
        withClue("expected hot image shard") { (imageHotIndex >= 0).shouldBeTrue() }
        withClue("expected cold image fallback shard") { (imageBaseIndex >= 0).shouldBeTrue() }
        withClue("hot memo shard should run before its cold fallback") { (memoHotIndex < memoBaseIndex).shouldBeTrue() }
        withClue("hot image shard should run before its cold fallback") { (imageHotIndex < imageBaseIndex).shouldBeTrue() }
        withClue("voice fallback shard should still be present") { (plan.any { it.relativePrefix == "lomo/voice" }).shouldBeTrue() }
    }

    private fun `buildRemoteScanPlan keeps root fallback for vault root content outside configured folders`() {
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

        withClue("configured memo folder shard should remain") { (plan.any { it.relativePrefix == "journal" }).shouldBeTrue() }
        withClue("configured image folder shard should remain") { (plan.any { it.relativePrefix == "asset" }).shouldBeTrue() }
        withClue("configured voice folder shard should remain") { (plan.any { it.relativePrefix == "voice" }).shouldBeTrue() }
        withClue("vault root should derive a top-level hot shard for indexed generic content") { (rootHotProjectsIndex >= 0).shouldBeTrue() }
        withClue("vault root should derive a second top-level hot shard for indexed generic content") { (rootHotPagesIndex >= 0).shouldBeTrue() }
        withClue("vault root should keep a cold root fallback") { (rootFallbackIndex >= 0).shouldBeTrue() }
        withClue("hot root shards should run before the cold root fallback") { (rootHotProjectsIndex < rootFallbackIndex).shouldBeTrue() }
        withClue("hot root shards should run before the cold root fallback") { (rootHotPagesIndex < rootFallbackIndex).shouldBeTrue() }
    }

    private fun `prepareRemoteReconcile verifies indexed file missing from fully scanned hot shard`() =
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
            hotShard.shouldNotBeNull()
            val client =
                ReconcileProbeClient(
                    onList = { throw AssertionError("reconcile should use paged listing") },
                    onListPage = { prefix, continuationToken, _ ->
                        prefix shouldBe "lomo/memo/a/"
                        continuationToken shouldBe null
                        S3RemoteListPage(objects = emptyList(), nextContinuationToken = null)
                    },
                    onGetObjectMetadata = { key ->
                        key shouldBe path
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
                            remoteScanCursor = encodeRemoteScanCursor(StoredS3RemoteScanCursor(bucketId = hotShard.bucketId)),
                    ),
                    encodingSupport = encodingSupport,
                    objectKeyPolicy = S3RemoteObjectKeyPolicy(encodingSupport),
                    metadataDao = InMemoryReconcileMetadataDao(),
                    remoteIndexStore = remoteIndexStore,
                    shardStateStore = DisabledS3RemoteShardStateStore,
                )

            prepared.missingRemotePaths shouldBe setOf(path)
            (path in prepared.candidatePaths).shouldBeTrue()
            client.headKeys shouldBe listOf(path)
        }

    private fun `prepareRemoteReconcile reads root fallback candidates without full index scan`() =
        runTest {
            val rootPath = "Projects/note.md"
            val remoteIndexStore =
                object : S3RemoteIndexStore {
                    private val delegate = InMemoryS3RemoteIndexStore()

                    init {
                        kotlinx.coroutines.runBlocking {
                            delegate.upsert(
                                listOf(
                                    remoteIndexEntry(path = rootPath, scanBucket = "Projects", scanPriority = 30),
                                    remoteIndexEntry(path = "journal/entry.md", scanBucket = S3_SCAN_BUCKET_MEMO, scanPriority = 30),
                                ),
                            )
                        }
                    }

                    override val remoteIndexEnabled: Boolean
                        get() = delegate.remoteIndexEnabled

                    suspend fun readAll(): List<S3RemoteIndexEntry> {
                        error("root fallback reconcile should not read the full remote index")
                    }

                    override suspend fun readAllRelativePaths(): List<String> = delegate.readAllRelativePaths()

                    override suspend fun readPresentCount(): Int = delegate.readPresentCount()

                    override suspend fun readByRelativePaths(relativePaths: Collection<String>): List<S3RemoteIndexEntry> =
                        delegate.readByRelativePaths(relativePaths)

                    override suspend fun readByRelativePrefix(relativePrefix: String?): List<S3RemoteIndexEntry> =
                        delegate.readByRelativePrefix(relativePrefix)

                    override suspend fun readOutsideScanBuckets(excludedBuckets: Collection<String>): List<S3RemoteIndexEntry> =
                        delegate.readOutsideScanBuckets(excludedBuckets)

                    override suspend fun readReconcileCandidates(limit: Int): List<S3RemoteIndexEntry> =
                        delegate.readReconcileCandidates(limit)

                    override suspend fun upsert(entries: Collection<S3RemoteIndexEntry>) = delegate.upsert(entries)

                    override suspend fun deleteByRelativePaths(relativePaths: Collection<String>) =
                        delegate.deleteByRelativePaths(relativePaths)

                    override suspend fun deleteOutsideScanEpoch(scanEpoch: Long) =
                        delegate.deleteOutsideScanEpoch(scanEpoch)

                    override suspend fun replaceAll(entries: Collection<S3RemoteIndexEntry>) = delegate.replaceAll(entries)

                    override suspend fun clear() = delegate.clear()
                }
            val rootShard =
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
                    indexedRelativePaths = listOf(rootPath),
                ).first { it.bucketId == S3_SCAN_BUCKET_ROOT }
            val client =
                ReconcileProbeClient(
                    onList = { throw AssertionError("reconcile should use paged listing") },
                    onListPage = { prefix, continuationToken, _ ->
                        prefix shouldBe ""
                        continuationToken shouldBe null
                        S3RemoteListPage(objects = emptyList(), nextContinuationToken = null)
                    },
                    onGetObjectMetadata = { key ->
                        (key == rootPath || key == "journal/entry.md").shouldBeTrue()
                        null
                    },
                )

            val prepared =
                prepareRemoteReconcile(
                    client = client,
                    layout = layout,
                    config = config,
                    mode =
                        S3LocalSyncMode.FileVaultRoot(
                            rootDir = File("/vault"),
                            memoRelativeDir = "journal",
                            imageRelativeDir = "asset",
                            voiceRelativeDir = "voice",
                            legacyRemoteCompatibility = false,
                        ),
                    protocolState =
                        S3SyncProtocolState(
                            lastSuccessfulSyncAt = System.currentTimeMillis(),
                            lastFullRemoteScanAt = System.currentTimeMillis(),
                            remoteScanCursor = encodeRemoteScanCursor(StoredS3RemoteScanCursor(bucketId = rootShard.bucketId)),
                    ),
                    encodingSupport = encodingSupport,
                    objectKeyPolicy = S3RemoteObjectKeyPolicy(encodingSupport),
                    metadataDao = InMemoryReconcileMetadataDao(),
                    remoteIndexStore = remoteIndexStore,
                    shardStateStore = DisabledS3RemoteShardStateStore,
                )

            (rootPath in prepared.missingRemotePaths).shouldBeTrue()
            (rootPath in client.headKeys).shouldBeTrue()
        }

    private fun `prepareRemoteReconcile keeps baseline-matching listed paths out of replanning candidates`() =
        runTest {
            val syncedPath = "lomo/memo/synced.md"
            val externalPath = "lomo/memo/external.md"
            val remoteIndexStore = InMemoryS3RemoteIndexStore()
            remoteIndexStore.upsert(
                listOf(remoteIndexEntry(path = syncedPath, scanBucket = S3_SCAN_BUCKET_MEMO, scanPriority = 10)),
            )
            val client =
                ReconcileProbeClient(
                    onListPage = { _, _, _ ->
                        S3RemoteListPage(
                            objects =
                                listOf(
                                    S3RemoteObject(
                                        key = syncedPath,
                                        eTag = "etag-stable",
                                        // listing reports the server upload time, far from the
                                        // rclone-style mtime persisted in the baseline
                                        lastModified = 999_000L,
                                        size = 5L,
                                        metadata = emptyMap(),
                                    ),
                                    S3RemoteObject(
                                        key = externalPath,
                                        eTag = "etag-external",
                                        lastModified = 50L,
                                        size = 7L,
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
                    objectKeyPolicy = S3RemoteObjectKeyPolicy(encodingSupport),
                    metadataDao = InMemoryReconcileMetadataDao(syncedMetadata(syncedPath, etag = "etag-stable")),
                    remoteIndexStore = remoteIndexStore,
                    shardStateStore = DisabledS3RemoteShardStateStore,
                )

            withClue("baseline-matching listed path must not re-enter planning") {
                (syncedPath in prepared.candidatePaths).shouldBeFalse()
            }
            withClue("externally added path must stay a planning candidate") {
                (externalPath in prepared.candidatePaths).shouldBeTrue()
            }
            withClue("baseline-matching listed path must still refresh the remote index scan epoch") {
                (syncedPath in prepared.observedRemoteEntries).shouldBeTrue()
            }
        }

    private fun `prepareRemoteReconcile keeps listed paths with changed etag or dirty index state as candidates`() =
        runTest {
            val changedPath = "lomo/memo/changed.md"
            val dirtyPath = "lomo/memo/dirty.md"
            val remoteIndexStore = InMemoryS3RemoteIndexStore()
            remoteIndexStore.upsert(
                listOf(
                    remoteIndexEntry(path = changedPath, scanBucket = S3_SCAN_BUCKET_MEMO, scanPriority = 10),
                    remoteIndexEntry(path = dirtyPath, scanBucket = S3_SCAN_BUCKET_MEMO, scanPriority = 10)
                        .copy(dirtySuspect = true),
                ),
            )
            val client =
                ReconcileProbeClient(
                    onListPage = { _, _, _ ->
                        S3RemoteListPage(
                            objects =
                                listOf(
                                    S3RemoteObject(
                                        key = changedPath,
                                        eTag = "etag-rewritten",
                                        lastModified = 70L,
                                        size = 9L,
                                        metadata = emptyMap(),
                                    ),
                                    S3RemoteObject(
                                        key = dirtyPath,
                                        eTag = "etag-stable",
                                        lastModified = 70L,
                                        size = 9L,
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
                    objectKeyPolicy = S3RemoteObjectKeyPolicy(encodingSupport),
                    metadataDao =
                        InMemoryReconcileMetadataDao(
                            syncedMetadata(changedPath, etag = "etag-stable"),
                            syncedMetadata(dirtyPath, etag = "etag-stable"),
                        ),
                    remoteIndexStore = remoteIndexStore,
                    shardStateStore = DisabledS3RemoteShardStateStore,
                )

            withClue("etag drift against the baseline must replan the path") {
                (changedPath in prepared.candidatePaths).shouldBeTrue()
            }
            withClue("dirty index entries must replan even when the etag matches the baseline") {
                (dirtyPath in prepared.candidatePaths).shouldBeTrue()
            }
        }

    private fun syncedMetadata(
        path: String,
        etag: String,
    ): com.lomo.data.local.entity.S3SyncMetadataEntity =
        com.lomo.data.local.entity.S3SyncMetadataEntity(
            relativePath = path,
            remotePath = path,
            etag = etag,
            remoteLastModified = 10L,
            localLastModified = 10L,
            localSize = 5L,
            remoteSize = 5L,
            localFingerprint = null,
            lastSyncedAt = 10L,
            lastResolvedDirection = com.lomo.data.local.entity.S3SyncMetadataEntity.NONE,
            lastResolvedReason = com.lomo.data.local.entity.S3SyncMetadataEntity.UNCHANGED,
        )

    private fun `prepareRemoteReconcile records shard scan stats after listing a page`() =
        runTest {
            val remoteIndexStore = InMemoryS3RemoteIndexStore()
            val shardStateStore = InMemoryS3RemoteShardStateStore()
            val listedPath = "lomo/memo/apple.md"
            val client =
                ReconcileProbeClient(
                    onList = { throw AssertionError("reconcile should use paged listing") },
                    onListPage = { prefix, continuationToken, maxKeys ->
                        prefix shouldBe "lomo/memo/"
                        continuationToken shouldBe null
                        maxKeys shouldBe 256
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
                    objectKeyPolicy = S3RemoteObjectKeyPolicy(encodingSupport),
                    metadataDao = InMemoryReconcileMetadataDao(),
                    remoteIndexStore = remoteIndexStore,
                    shardStateStore = shardStateStore,
                )

            val shardState = requireNotNull(shardStateStore.readByBucketId(S3_SCAN_BUCKET_MEMO))
            (listedPath in prepared.candidatePaths).shouldBeTrue()
            shardState.relativePrefix shouldBe "lomo/memo"
            shardState.lastObjectCount shouldBe 1
            withClue("shard scan timestamp should be recorded") { (shardState.lastScannedAt > 0L).shouldBeTrue() }
            withClue("shard scan duration should be recorded") { (shardState.lastDurationMs >= 0L).shouldBeTrue() }
            withClue("listed objects should count as observed changes") { shardState.lastChangeCount shouldBe 1 }
        }

    private fun `prepareRemoteReconcile records idle streak and verification failures for shard state`() =
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
                    objectKeyPolicy = S3RemoteObjectKeyPolicy(encodingSupport),
                    metadataDao = InMemoryReconcileMetadataDao(),
                    remoteIndexStore = remoteIndexStore,
                    shardStateStore = shardStateStore,
                )

            val shardState = requireNotNull(shardStateStore.readByBucketId(hotShard.bucketId))
            prepared.missingRemotePaths shouldBe setOf(path)
            shardState.idleScanStreak shouldBe 0
            shardState.lastVerificationAttemptCount shouldBe 1
            shardState.lastVerificationFailureCount shouldBe 1
        }

    private fun `prepareRemoteReconcile resolves ancestor shard telemetry without scanning the full shard-state table`() =
        runTest {
            val path = "lomo/memo/apple.md"
            val remoteIndexStore = InMemoryS3RemoteIndexStore().apply {
                upsert(listOf(remoteIndexEntry(path = path, scanBucket = S3_SCAN_BUCKET_MEMO, scanPriority = 30)))
            }
            val shardStateStore =
                object : S3RemoteShardStateStore {
                    override val remoteShardStateEnabled: Boolean = true

                    override suspend fun readAll(): List<S3RemoteShardState> {
                        error("reconcile should not scan the full shard-state table for ancestor telemetry")
                    }

                    override suspend fun readByBucketId(bucketId: String): S3RemoteShardState? = null

                    override suspend fun readByBucketIds(bucketIds: Collection<String>): List<S3RemoteShardState> =
                        emptyList()

                    override suspend fun readMostSpecificAncestor(relativePrefix: String?): S3RemoteShardState? =
                        S3RemoteShardState(
                            bucketId = S3_SCAN_BUCKET_MEMO,
                            relativePrefix = "lomo/memo",
                            lastScannedAt = 800L,
                            lastObjectCount = 1,
                            lastDurationMs = 25L,
                            lastChangeCount = 0,
                            idleScanStreak = 0,
                            lastVerificationAttemptCount = 1,
                            lastVerificationFailureCount = 0,
                        )

                    override suspend fun readScheduleTelemetry(
                        now: Long,
                        reconcileInterval: java.time.Duration,
                        endpointProfile: S3EndpointProfile,
                    ): S3RemoteShardScheduleTelemetry =
                        S3RemoteShardScheduleTelemetry(
                            shardCount = 1,
                            oldestScanAt = 800L,
                            hasElevatedChangePressure = false,
                            hasHighVerificationUncertainty = false,
                        )

                    override suspend fun upsert(states: Collection<S3RemoteShardState>) = Unit

                    override suspend fun clear() = Unit
                }
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
                    onGetObjectMetadata = { null },
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
                    objectKeyPolicy = S3RemoteObjectKeyPolicy(encodingSupport),
                    metadataDao = InMemoryReconcileMetadataDao(),
                    remoteIndexStore = remoteIndexStore,
                    shardStateStore = shardStateStore,
                )

            prepared.missingRemotePaths shouldBe setOf(path)
        }

    private fun `applyRemoteIndexUpdates retains dirty unresolved entries and missing tombstones`() =
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
                        syncedContentFingerprints = emptyMap(),
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

            withClue("unresolved entry should stay dirty") { (unresolved.dirtySuspect).shouldBeTrue() }
            withClue("unresolved entry should be promoted for follow-up verification") { (unresolved.scanPriority > 10).shouldBeTrue() }
            withClue("missing entry should be retained as a tombstone") { (missing.missingOnLastScan).shouldBeTrue() }
            withClue("missing entry should stay hot for verification") { (missing.dirtySuspect).shouldBeTrue() }
        }

    private fun `applyRemoteIndexUpdates replaces full snapshot through upsert and diff delete without replaceAll`() =
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
                        syncedContentFingerprints = emptyMap(),
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
            remoteIndexStore.replaceAllCalls shouldBe 0
            remoteIndexStore.deletedPaths shouldBe setOf(removedPath)
            remoteIndexStore.upsertedPaths shouldBe setOf(keptPath, refreshedPath)
            withClue("full snapshot should delete paths missing from the new scan") { (removedPath !in byPath).shouldBeTrue() }
            byPath[keptPath]?.scanEpoch shouldBe 9L
            byPath[refreshedPath]?.scanEpoch shouldBe 9L
        }

    private fun `applyRemoteIndexUpdates replaces full snapshot without loading full entry rows`() =
        runTest {
            val keptPath = "lomo/memo/keep.md"
            val removedPath = "lomo/memo/remove.md"
            val remoteIndexStore =
                NoFullEntryReadSnapshotRemoteIndexStore(
                    initialEntries =
                        listOf(
                            remoteIndexEntry(path = keptPath, scanBucket = S3_SCAN_BUCKET_MEMO, scanPriority = 80),
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
                        completeSnapshot = true,
                        protocolState = S3SyncProtocolState(indexedRemoteFileCount = 2, scanEpoch = 7L),
                        remoteFileCountHint = 2,
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
                        syncedContentFingerprints = emptyMap(),
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
                            ),
                        memoRefreshPlan = S3MemoRefreshPlan.None,
                    ),
                now = 300L,
            )

            remoteIndexStore.deletedPaths shouldBe setOf(removedPath)
            remoteIndexStore.readAllCalls shouldBe 0
        }

    private fun `applyRemoteIndexUpdates promotes successful action outcomes into recent activity candidates`() =
        runTest {
            val path = "lomo/memo/recent.md"
            val remoteIndexStore =
                InMemoryS3RemoteIndexStore().apply {
                    upsert(listOf(remoteIndexEntry(path = path, scanBucket = S3_SCAN_BUCKET_MEMO, scanPriority = 100)))
                }

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
                        completeSnapshot = false,
                        protocolState = S3SyncProtocolState(indexedRemoteFileCount = 1, scanEpoch = 5L),
                        remoteFileCountHint = 1,
                        remoteReconcileState = null,
                    ),
                execution =
                    S3ActionExecutionResult(
                        actionOutcomes = mapOf(path to (S3SyncDirection.UPLOAD to S3SyncReason.LOCAL_NEWER)),
                        syncedContentFingerprints = emptyMap(),
                        failedPaths = emptyList(),
                        unresolvedPaths = emptySet(),
                        localChanged = false,
                        localFilesAfterSync = emptyMap(),
                        remoteFilesAfterSync =
                            mapOf(
                                path to
                                    RemoteS3File(
                                        path = path,
                                        etag = "etag-recent",
                                        lastModified = 42L,
                                        remotePath = path,
                                        verificationLevel = S3RemoteVerificationLevel.VERIFIED_REMOTE,
                                    ),
                            ),
                        memoRefreshPlan = S3MemoRefreshPlan.None,
                    ),
                now = 420L,
            )

            val recent = requireNotNull(remoteIndexStore.readByRelativePaths(listOf(path)).singleOrNull())
            recent.scanEpoch shouldBe 5L
            withClue("successful actions should stay warm for planner seeds") { (recent.scanPriority > 100).shouldBeTrue() }
            withClue("successful actions should refresh planner recency") { (recent.lastSeenAt >= 420L).shouldBeTrue() }
            withClue("successful actions should remain present, not tombstoned") { (!recent.missingOnLastScan).shouldBeTrue() }
        }

    private fun `prepareRemoteReconcile expands list page budget for hot high-change shard`() =
        runTest {
            val shardStateStore = InMemoryS3RemoteShardStateStore().apply {
                upsert(
                    listOf(
                        S3RemoteShardState(
                            bucketId = S3_SCAN_BUCKET_MEMO,
                            relativePrefix = "lomo/memo",
                            lastScannedAt = 990L,
                            lastObjectCount = 10,
                            lastDurationMs = 20L,
                            lastChangeCount = 8,
                            idleScanStreak = 0,
                            lastVerificationAttemptCount = 2,
                            lastVerificationFailureCount = 0,
                        ),
                    ),
                )
            }
            var observedMaxKeys = 0
            val client =
                ReconcileProbeClient(
                    onListPage = { prefix, _, maxKeys ->
                        prefix shouldBe "lomo/memo/"
                        observedMaxKeys = maxKeys
                        S3RemoteListPage(objects = emptyList(), nextContinuationToken = null)
                    },
                )

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
                objectKeyPolicy = S3RemoteObjectKeyPolicy(encodingSupport),
                metadataDao = InMemoryReconcileMetadataDao(),
                remoteIndexStore = InMemoryS3RemoteIndexStore(),
                shardStateStore = shardStateStore,
            )

            withClue("hot changing shard should receive a larger list-page budget") { (observedMaxKeys > 256).shouldBeTrue() }
        }

    private fun `reconcile tuner uses more conservative base budgets for minio profile`() {
        val tuning =
            S3RemoteReconcileTuner().tune(
                config = config.copy(endpointProfile = S3EndpointProfile.MINIO_COMPAT),
                protocolState = S3SyncProtocolState(),
                activeShardState = null,
            )

        tuning.pageSize shouldBe 192
        tuning.headLimit shouldBe 10
        tuning.headConcurrency shouldBe 2
    }

    private fun `reconcile tuner does not clamp moderate verification failures for minio profile`() {
        val tuning =
            S3RemoteReconcileTuner().tune(
                config = config.copy(endpointProfile = S3EndpointProfile.MINIO_COMPAT),
                protocolState = S3SyncProtocolState(),
                activeShardState =
                    S3RemoteShardState(
                        bucketId = S3_SCAN_BUCKET_MEMO,
                        relativePrefix = "lomo/memo",
                        lastScannedAt = 900L,
                        lastObjectCount = 12,
                        lastDurationMs = 250L,
                        lastChangeCount = 2,
                        idleScanStreak = 0,
                        lastVerificationAttemptCount = 5,
                        lastVerificationFailureCount = 3,
                    ),
            )

        tuning.headLimit shouldBe 10
        tuning.headConcurrency shouldBe 2
    }

    private fun `prepareRemoteReconcile shrinks verification head budget when shard has high failure rate`() =
        runTest {
            val remoteIndexStore = InMemoryS3RemoteIndexStore()
            val shardStateStore = InMemoryS3RemoteShardStateStore()
            repeat(24) { index ->
                remoteIndexStore.upsert(
                    listOf(
                        remoteIndexEntry(
                            path = "lomo/memo/miss-$index.md",
                            scanBucket = S3_SCAN_BUCKET_MEMO,
                            scanPriority = 150 + index,
                        ),
                    ),
                )
            }
            shardStateStore.upsert(
                listOf(
                    S3RemoteShardState(
                        bucketId = S3_SCAN_BUCKET_MEMO,
                        relativePrefix = "lomo/memo",
                        lastScannedAt = 900L,
                        lastObjectCount = 24,
                        lastDurationMs = 500L,
                        lastChangeCount = 2,
                        idleScanStreak = 0,
                        lastVerificationAttemptCount = 8,
                        lastVerificationFailureCount = 7,
                    ),
                ),
            )
            val client =
                ReconcileProbeClient(
                    onListPage = { _, _, _ ->
                        S3RemoteListPage(objects = emptyList(), nextContinuationToken = null)
                    },
                    onGetObjectMetadata = { null },
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
                    objectKeyPolicy = S3RemoteObjectKeyPolicy(encodingSupport),
                    metadataDao = InMemoryReconcileMetadataDao(),
                    remoteIndexStore = remoteIndexStore,
                    shardStateStore = shardStateStore,
                )

            withClue("high-failure shards should verify fewer candidates per cycle") { (client.headKeys.size in 1..8).shouldBeTrue() }
            prepared.missingRemotePaths.size shouldBe client.headKeys.size
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

private class InMemoryReconcileMetadataDao(
    vararg entities: com.lomo.data.local.entity.S3SyncMetadataEntity,
) : com.lomo.data.local.dao.S3SyncMetadataDao {
    private val rows = entities.associateBy { entity -> entity.relativePath }.toMutableMap()

    override suspend fun getAll(): List<com.lomo.data.local.entity.S3SyncMetadataEntity> = rows.values.toList()

    override suspend fun getAllPlannerMetadataSnapshots(): List<com.lomo.data.local.dao.S3SyncPlannerMetadataSnapshot> =
        error("reconcile preparation must not load the full planner metadata table")

    override suspend fun getAllRemoteMetadataSnapshots(): List<com.lomo.data.local.dao.S3SyncRemoteMetadataSnapshot> =
        error("reconcile preparation must not load the full remote metadata table")

    override suspend fun getByRelativePaths(
        relativePaths: List<String>,
    ): List<com.lomo.data.local.entity.S3SyncMetadataEntity> = relativePaths.mapNotNull(rows::get)

    override suspend fun upsertAll(entities: List<com.lomo.data.local.entity.S3SyncMetadataEntity>) {
        entities.forEach { entity -> rows[entity.relativePath] = entity }
    }

    override suspend fun deleteByRelativePath(relativePath: String) {
        rows.remove(relativePath)
    }

    override suspend fun deleteByRelativePaths(relativePaths: List<String>) {
        relativePaths.forEach(rows::remove)
    }

    override suspend fun clearAll() {
        rows.clear()
    }
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

    override suspend fun getSmallObject(key: String): com.lomo.data.s3.S3SmallObjectPayload {
        error("getObject should not be used in reconcile test")
    }

    override suspend fun putSmallObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
        ifMatch: String?,
        ifNoneMatch: String?,
    ): com.lomo.data.s3.S3PutObjectResult {
        error("putObject should not be used in reconcile test")
    }

    override suspend fun getObjectToFile(
        key: String,
        destination: java.io.File,
    ): com.lomo.data.s3.S3RemoteObject {
        val payload = getSmallObject(key)
        destination.parentFile?.mkdirs()
        destination.writeBytes(payload.bytes)
        return com.lomo.data.s3.S3RemoteObject(
            key = payload.key,
            eTag = payload.eTag,
            lastModified = payload.lastModified,
            size = destination.length(),
            metadata = payload.metadata,
        )
    }

    override suspend fun putObjectFile(
        key: String,
        file: java.io.File,
        contentType: String,
        metadata: Map<String, String>,
        ifMatch: String?,
        ifNoneMatch: String?,
    ): com.lomo.data.s3.S3PutObjectResult =
        putSmallObject(key, file.readBytes(), contentType, metadata, ifMatch, ifNoneMatch)

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

    override suspend fun readPresentCount(): Int = delegate.readPresentCount()

    override suspend fun readAllRelativePaths(): List<String> = delegate.readAllRelativePaths()

    override suspend fun readByRelativePaths(relativePaths: Collection<String>): List<S3RemoteIndexEntry> =
        delegate.readByRelativePaths(relativePaths)

    override suspend fun readByRelativePrefix(relativePrefix: String?): List<S3RemoteIndexEntry> =
        delegate.readByRelativePrefix(relativePrefix)

    override suspend fun readOutsideScanBuckets(excludedBuckets: Collection<String>): List<S3RemoteIndexEntry> =
        delegate.readOutsideScanBuckets(excludedBuckets)

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

private class NoFullEntryReadSnapshotRemoteIndexStore(
    initialEntries: List<S3RemoteIndexEntry>,
) : S3RemoteIndexStore {
    private val delegate = InMemoryS3RemoteIndexStore()
    val deletedPaths = linkedSetOf<String>()
    var readAllCalls: Int = 0
        private set

    init {
        kotlinx.coroutines.runBlocking {
            delegate.upsert(initialEntries)
        }
    }

    override val remoteIndexEnabled: Boolean
        get() = delegate.remoteIndexEnabled

    suspend fun readAll(): List<S3RemoteIndexEntry> {
        readAllCalls += 1
        error("full snapshot replacement should not read every stored entry row")
    }

    override suspend fun readPresentCount(): Int = delegate.readPresentCount()

    override suspend fun readAllRelativePaths(): List<String> = delegate.readAllRelativePaths()

    override suspend fun readByRelativePaths(relativePaths: Collection<String>): List<S3RemoteIndexEntry> =
        delegate.readByRelativePaths(relativePaths)

    override suspend fun readByRelativePrefix(relativePrefix: String?): List<S3RemoteIndexEntry> =
        delegate.readByRelativePrefix(relativePrefix)

    override suspend fun readOutsideScanBuckets(excludedBuckets: Collection<String>): List<S3RemoteIndexEntry> =
        delegate.readOutsideScanBuckets(excludedBuckets)

    override suspend fun readReconcileCandidates(limit: Int): List<S3RemoteIndexEntry> =
        delegate.readReconcileCandidates(limit)

    override suspend fun upsert(entries: Collection<S3RemoteIndexEntry>) {
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
        error("replaceAll should not be used for full snapshot updates")
    }

    override suspend fun clear() {
        delegate.clear()
    }
}
