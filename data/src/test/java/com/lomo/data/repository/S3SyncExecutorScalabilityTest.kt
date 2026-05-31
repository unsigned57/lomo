package com.lomo.data.repository

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.dao.S3SyncPlannerMetadataSnapshot
import com.lomo.data.local.dao.S3SyncRemoteMetadataSnapshot
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.local.entity.S3SyncMetadataEntity
import com.lomo.data.s3.LomoS3Client
import com.lomo.data.s3.LomoS3ClientFactory
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.s3.S3PutObjectResult
import com.lomo.data.s3.S3RemoteObject
import com.lomo.data.s3.S3SmallObjectPayload
import com.lomo.data.source.FileMetadata
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.testing.fakes.FakeFileDataSource
import com.lomo.data.testing.fakes.FakeS3SyncMetadataDao
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncReason
import com.lomo.domain.model.S3SyncResult
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.Collections
import kotlin.math.max
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.types.shouldBeInstanceOf

/*
 * Behavior Contract:
 * - Unit under test: S3SyncExecutor
 * - Behavior focus: preparation should overlap local, remote, and metadata discovery; multi-action sync should overlap independent uploads; metadata persistence should stay incremental and batch stale-row cleanup; and legacy attachment downloads should reuse one resolved local sync mode without extra media rescans.
 * - Observable outcomes: peak concurrent preparation and upload work, returned sync outcomes, metadata DAO clear/replace usage, stale metadata delete batching, metadata upsert scope, local media listing count, and local sync configuration collection count.
 * - TDD proof: Fails before the fix because metadata loading waits for local/remote scans, S3 actions run strictly serially, stale metadata cleanup deletes one row at a time, local sync mode is re-resolved for each action, metadata persistence replaces the full table, and legacy direct-root attachment downloads trigger a second full media scan.
 * - Excludes: AWS SDK transport correctness, Room generated DAO code, WorkManager scheduling, and UI rendering.
 */
class S3SyncExecutorScalabilityTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("performSync overlaps independent uploads within one sync pass") { `performSync overlaps independent uploads within one sync pass`() }

        test("performSync overlaps local scan and remote listing during preparation") { `performSync overlaps local scan and remote listing during preparation`() }

        test("performSync overlaps metadata load with local and remote listing during preparation") { `performSync overlaps metadata load with local and remote listing during preparation`() }

        test("performSync overlaps full remote shard listings during manifest-free snapshot build") { `performSync overlaps full remote shard listings during manifest-free snapshot build`() }

        test("performSync persists only changed metadata without clearing existing rows") { `performSync persists only changed metadata without clearing existing rows`() }

        test("performSync batches stale metadata deletions") { `performSync batches stale metadata deletions`() }

        test("performSync does not rescan local files after download updates local state") { `performSync does not rescan local files after download updates local state`() }

        test("performSync does not rescan legacy media store after direct-root attachment download") { `performSync does not rescan legacy media store after direct-root attachment download`() }

        test("performSync reuses resolved legacy sync mode during attachment download") { `performSync reuses resolved legacy sync mode during attachment download`() }
    }


    @MockK(relaxed = true)
    private lateinit var dataStore: LomoDataStore

    @MockK(relaxed = true)
    private lateinit var credentialStore: S3CredentialStore

    private val markdownStorageDataSource = FakeFileDataSource()

    @MockK(relaxed = true)
    private lateinit var localMediaSyncStore: LocalMediaSyncStore

    @MockK(relaxed = true)
    private lateinit var memoSynchronizer: MemoSynchronizer

    private fun setUp() {
        MockKAnnotations.init(this)

        every { dataStore.s3SyncEnabled } returns flowOf(true)
        every { dataStore.s3EndpointUrl } returns flowOf("https://s3.example.com")
        every { dataStore.s3Region } returns flowOf("us-east-1")
        every { dataStore.s3Bucket } returns flowOf("bucket")
        every { dataStore.s3Prefix } returns flowOf("")
        every { dataStore.s3PathStyle } returns flowOf("path_style")
        every { dataStore.s3EncryptionMode } returns flowOf("none")
        every { dataStore.s3RcloneFilenameEncryption } returns flowOf("standard")
        every { dataStore.s3RcloneFilenameEncoding } returns flowOf("base64")
        every { dataStore.s3RcloneDirectoryNameEncryption } returns flowOf(true)
        every { dataStore.s3RcloneDataEncryptionEnabled } returns flowOf(true)
        every { dataStore.s3RcloneEncryptedSuffix } returns flowOf(".bin")
        every { dataStore.s3LocalSyncDirectory } returns flowOf(null)
        every { dataStore.rootDirectory } returns flowOf("/memo")
        every { dataStore.rootUri } returns flowOf(null)
        every { dataStore.imageDirectory } returns flowOf("/images")
        every { dataStore.imageUri } returns flowOf(null)
        every { dataStore.voiceDirectory } returns flowOf("/voice")
        every { dataStore.voiceUri } returns flowOf(null)
        every { credentialStore.getAccessKeyId() } returns "access"
        every { credentialStore.getSecretAccessKey() } returns "secret"
        every { credentialStore.getSessionToken() } returns null
        every { credentialStore.getEncryptionPassword() } returns null
        every { credentialStore.getEncryptionPassword2() } returns null

        coEvery { dataStore.updateS3LastSyncTime(any()) } returns Unit
        markdownStorageDataSource.readFileInResult = { _, filename ->
            "# ${filename.removeSuffix(".md")}"
        }
        coEvery { localMediaSyncStore.listFiles(any()) } returns emptyMap()
    }

    private fun `performSync overlaps independent uploads within one sync pass`() =
        runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            val client = ParallelUploadProbeClient()
            val metadataDao = RecordingMetadataDao()
            markdownStorageDataSource.listMetadataInResult = {
                listOf(
                    FileMetadata(filename = "first.md", lastModified = 10L),
                    FileMetadata(filename = "second.md", lastModified = 20L),
                )
            }

            val executor = createExecutor(client = client, metadataDao = metadataDao)

            val result = executor.performSync()

            val success =
                withClue(result.toString()) {
                    result.shouldBeInstanceOf<S3SyncResult.Success>()
                }
            success.message shouldBe "S3 sync completed"
            success.outcomes.map { it.direction to it.reason } shouldBe listOf(
                    S3SyncDirection.UPLOAD to S3SyncReason.LOCAL_ONLY,
                    S3SyncDirection.UPLOAD to S3SyncReason.LOCAL_ONLY,
                )
            withClue("Expected at least 2 concurrent uploads but saw ${client.maxConcurrentUploads}") { (client.maxConcurrentUploads >= 2).shouldBeTrue() }
        }

    private fun `performSync overlaps local scan and remote listing during preparation`() =
        runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            val probe = ConcurrentScanProbe(expected = 2)
            val client = ParallelListingProbeClient(probe)
            val metadataDao = RecordingMetadataDao()
            markdownStorageDataSource.listMetadataInResult = {
                probe.track()
                listOf(FileMetadata(filename = "note.md", lastModified = 10L))
            }

            val executor = createExecutor(client = client, metadataDao = metadataDao)

            val result = executor.performSync()

            (result is S3SyncResult.Review).shouldBeTrue()
            withClue("Expected local scan and remote listing to overlap but saw ${probe.maxConcurrent}") { (probe.maxConcurrent >= 2).shouldBeTrue() }
        }

    private fun `performSync overlaps metadata load with local and remote listing during preparation`() =
        runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            val probe = ConcurrentScanProbe(expected = 3)
            val client = ParallelListingProbeClient(probe)
            val metadataDao = object : FakeS3SyncMetadataDao() {
                override suspend fun getAll(): List<S3SyncMetadataEntity> {
                    probe.track()
                    return emptyList()
                }
            }
            markdownStorageDataSource.listMetadataInResult = {
                probe.track()
                listOf(FileMetadata(filename = "note.md", lastModified = 10L))
            }

            val executor = createExecutor(client = client, metadataDao = metadataDao)

            val result = executor.performSync()

            (result is S3SyncResult.Review).shouldBeTrue()
            withClue("Expected local scan, remote listing, and metadata load to overlap but saw ${probe.maxConcurrent}") { (probe.maxConcurrent >= 3).shouldBeTrue() }
        }

    private fun `performSync overlaps full remote shard listings during manifest-free snapshot build`() =
        runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            val probe = ConcurrentScanProbe(expected = 2)
            val client = ParallelPagedListingProbeClient(probe)
            val metadataDao = RecordingMetadataDao()
            markdownStorageDataSource.listMetadataInResult = { emptyList() }

            val executor = createExecutor(client = client, metadataDao = metadataDao)

            val result = executor.performSync()

            val success = result as S3SyncResult.Success
            success.message shouldBe "S3 already up to date"
            withClue("Expected full remote shard listing to overlap but saw ${client.maxConcurrentPages}") { (client.maxConcurrentPages >= 2).shouldBeTrue() }
            client.listedPrefixes.toSet() shouldBe setOf("lomo/memo/", "lomo/images/", "lomo/voice/")
        }

    private fun `performSync persists only changed metadata without clearing existing rows`() =
        runTest {
            val stablePath = "lomo/memo/stable.md"
            val freshPath = "lomo/memo/fresh.md"
            val metadataDao =
                RecordingMetadataDao(
                    initial =
                        listOf(
                            S3SyncMetadataEntity(
                                relativePath = stablePath,
                                remotePath = stablePath,
                                etag = "etag-stable",
                                remoteLastModified = 100L,
                                localLastModified = 100L,
                                lastSyncedAt = 100L,
                                lastResolvedDirection = "NONE",
                                lastResolvedReason = "UNCHANGED",
                            ),
                        ),
                )
            val client =
                StaticListingClient(
                    remoteObjects =
                        listOf(
                            S3RemoteObject(
                                key = stablePath,
                                eTag = "etag-stable",
                                lastModified = 100L,
                                metadata = emptyMap(),
                            ),
                        ),
                    uploadedEtags =
                        mapOf(
                            freshPath to "etag-fresh",
                        ),
                )
            markdownStorageDataSource.listMetadataInResult = {
                listOf(
                    FileMetadata(filename = "stable.md", lastModified = 100L),
                    FileMetadata(filename = "fresh.md", lastModified = 200L),
                )
            }

            val executor = createExecutor(client = client, metadataDao = metadataDao)

            val result = executor.performSync()

            val success = result as S3SyncResult.Success
            success.message shouldBe "S3 sync completed"
            success.outcomes.map { it.direction to it.reason } shouldBe listOf(S3SyncDirection.UPLOAD to S3SyncReason.LOCAL_ONLY)
            metadataDao.replaceAllCalls shouldBe 0
            metadataDao.clearAllCalls shouldBe 0
            metadataDao.upsertedPaths() shouldBe listOf(freshPath)
        }

    private fun `performSync batches stale metadata deletions`() =
        runTest {
            val firstStalePath = "lomo/memo/old-a.md"
            val secondStalePath = "lomo/images/old-b.png"
            val metadataDao =
                RecordingMetadataDao(
                    initial =
                        listOf(
                            staleMetadata(firstStalePath),
                            staleMetadata(secondStalePath),
                        ),
                )
            val client = StaticListingClient(remoteObjects = emptyList(), uploadedEtags = emptyMap())
            markdownStorageDataSource.listMetadataInResult = { emptyList() }

            val executor = createExecutor(client = client, metadataDao = metadataDao)

            val result = executor.performSync()

            val success = result as S3SyncResult.Success
            success.message shouldBe "S3 already up to date"
            metadataDao.deletedPathBatches() shouldBe listOf(listOf(secondStalePath, firstStalePath))
            metadataDao.deletedSinglePaths() shouldBe emptyList<String>()
        }

    private fun `performSync does not rescan local files after download updates local state`() =
        runTest {
            val memoRemotePath = "lomo/memo/note.md"
            val metadataDao = RecordingMetadataDao()
            val client =
                DownloadOnlyClient(
                    remoteObjects =
                        listOf(
                            S3RemoteObject(
                                key = memoRemotePath,
                                eTag = "etag-note",
                                lastModified = 30L,
                                metadata = emptyMap(),
                            ),
                        ),
                    payloads =
                        mapOf(
                            memoRemotePath to
                                S3SmallObjectPayload(
                                    key = memoRemotePath,
                                    eTag = "etag-note",
                                    lastModified = 30L,
                                    metadata = emptyMap(),
                                    bytes = "# note".toByteArray(),
                                ),
                        ),
                )
            markdownStorageDataSource.listMetadataInResult = { emptyList() }

            val executor = createExecutor(client = client, metadataDao = metadataDao)

            val result = executor.performSync()

            val success = result as S3SyncResult.Success
            success.message shouldBe "S3 sync completed"
            success.outcomes.map { it.direction to it.reason } shouldBe listOf(S3SyncDirection.DOWNLOAD to S3SyncReason.REMOTE_ONLY)
            markdownStorageDataSource.listMetadataInCalls.size shouldBe 1
        }

    private fun `performSync does not rescan legacy media store after direct-root attachment download`() =
        runTest {
            val mediaRoot = Files.createTempDirectory("s3-sync-media-root").toFile()
            val imageRoot = java.io.File(mediaRoot, "images")
            val voiceRoot = java.io.File(mediaRoot, "voice")
            try {
                imageRoot.mkdirs()
                voiceRoot.mkdirs()
                every { dataStore.imageDirectory } returns flowOf(imageRoot.absolutePath)
                every { dataStore.voiceDirectory } returns flowOf(voiceRoot.absolutePath)
                val imagePath = "lomo/images/cover.png"
                val metadataDao = RecordingMetadataDao()
                val client =
                    DownloadOnlyClient(
                        remoteObjects =
                            listOf(
                                S3RemoteObject(
                                    key = imagePath,
                                    eTag = "etag-image",
                                    lastModified = 40L,
                                    metadata = emptyMap(),
                                ),
                            ),
                        payloads =
                            mapOf(
                                imagePath to
                                    S3SmallObjectPayload(
                                        key = imagePath,
                                        eTag = "etag-image",
                                        lastModified = 40L,
                                        metadata = emptyMap(),
                                        bytes = byteArrayOf(1, 2, 3),
                                    ),
                            ),
                    )
                markdownStorageDataSource.listMetadataInResult = { emptyList() }
                coEvery { localMediaSyncStore.writeBytes(imagePath, any(), any()) } coAnswers {
                    imageRoot.mkdirs()
                    java.io.File(imageRoot, "cover.png").writeBytes(secondArg())
                }

                val executor = createExecutor(client = client, metadataDao = metadataDao)

                val result = executor.performSync()

                val success = result as S3SyncResult.Success
                success.message shouldBe "S3 sync completed"
                success.outcomes.map { it.direction to it.reason } shouldBe listOf(S3SyncDirection.DOWNLOAD to S3SyncReason.REMOTE_ONLY)
                coVerify(exactly = 1) { localMediaSyncStore.listFiles(any()) }
            } finally {
                mediaRoot.deleteRecursively()
            }
        }

    private fun `performSync reuses resolved legacy sync mode during attachment download`() =
        runTest {
            val mediaRoot = Files.createTempDirectory("s3-sync-legacy-mode-root").toFile()
            val imageRoot = java.io.File(mediaRoot, "images")
            val voiceRoot = java.io.File(mediaRoot, "voice")
            try {
                imageRoot.mkdirs()
                voiceRoot.mkdirs()
                val s3LocalSyncDirectoryCollections = AtomicInteger(0)
                val rootDirectoryCollections = AtomicInteger(0)
                val rootUriCollections = AtomicInteger(0)
                val imageDirectoryCollections = AtomicInteger(0)
                val imageUriCollections = AtomicInteger(0)
                val voiceDirectoryCollections = AtomicInteger(0)
                val voiceUriCollections = AtomicInteger(0)
                every { dataStore.s3LocalSyncDirectory } returns countingFlow(null, s3LocalSyncDirectoryCollections)
                every { dataStore.rootDirectory } returns countingFlow("/memo", rootDirectoryCollections)
                every { dataStore.rootUri } returns countingFlow(null, rootUriCollections)
                every { dataStore.imageDirectory } returns
                    countingFlow(imageRoot.absolutePath, imageDirectoryCollections)
                every { dataStore.imageUri } returns countingFlow(null, imageUriCollections)
                every { dataStore.voiceDirectory } returns
                    countingFlow(voiceRoot.absolutePath, voiceDirectoryCollections)
                every { dataStore.voiceUri } returns countingFlow(null, voiceUriCollections)

                val imagePath = "lomo/images/cover.png"
                val metadataDao = RecordingMetadataDao()
                val client =
                    DownloadOnlyClient(
                        remoteObjects =
                            listOf(
                                S3RemoteObject(
                                    key = imagePath,
                                    eTag = "etag-image",
                                    lastModified = 40L,
                                    metadata = emptyMap(),
                                ),
                            ),
                        payloads =
                            mapOf(
                                imagePath to
                                    S3SmallObjectPayload(
                                        key = imagePath,
                                        eTag = "etag-image",
                                        lastModified = 40L,
                                        metadata = emptyMap(),
                                        bytes = byteArrayOf(1, 2, 3),
                                    ),
                            ),
                    )
                markdownStorageDataSource.listMetadataInResult = { emptyList() }
                coEvery { localMediaSyncStore.writeBytes(imagePath, any(), any()) } coAnswers {
                    imageRoot.mkdirs()
                    java.io.File(imageRoot, "cover.png").writeBytes(secondArg())
                }

                val executor = createExecutor(client = client, metadataDao = metadataDao)

                val result = executor.performSync()

                val success = result as S3SyncResult.Success
                success.message shouldBe "S3 sync completed"
                s3LocalSyncDirectoryCollections.get() shouldBe 1
                rootDirectoryCollections.get() shouldBe 2
                rootUriCollections.get() shouldBe 2
                imageDirectoryCollections.get() shouldBe 2
                imageUriCollections.get() shouldBe 2
                voiceDirectoryCollections.get() shouldBe 2
                voiceUriCollections.get() shouldBe 2
            } finally {
                mediaRoot.deleteRecursively()
            }
        }

    private fun createExecutor(
        client: LomoS3Client,
        metadataDao: S3SyncMetadataDao,
    ): S3SyncExecutor {
        val runtime =
            S3SyncRepositoryContext(
                dataStore = dataStore,
                credentialStore = credentialStore,
                clientFactory = LomoS3ClientFactory { client },
                markdownStorageDataSource = markdownStorageDataSource,
                localMediaSyncStore = localMediaSyncStore,
                metadataDao = metadataDao,
                memoSynchronizer = memoSynchronizer,
                planner = S3SyncPlanner(timestampToleranceMs = 0L),
                stateHolder = S3SyncStateHolder(),
                performanceTuner = DisabledSyncPerformanceTuner,
                transactionRunner = NoOpS3SyncTransactionRunner,
            )
        val encodingSupport = S3SyncEncodingSupport()
        val fileBridge = S3SyncFileBridge(runtime, encodingSupport)
        return S3SyncExecutor(
            runtime = runtime,
            support = S3SyncRepositorySupport(runtime),
            encodingSupport = encodingSupport,
            fileBridge = fileBridge,
            actionApplier = S3SyncActionApplier(runtime, encodingSupport, fileBridge),
            lifecycleRunner = testRemoteSyncLifecycleRunner(),
            protocolStateStore = DisabledS3SyncProtocolStateStore,
            localChangeJournalStore = DisabledS3LocalChangeJournalStore,
            remoteIndexStore = DisabledS3RemoteIndexStore,
            remoteShardStateStore = DisabledS3RemoteShardStateStore,
            pendingConflictStore = InMemoryPendingSyncConflictStore(),
            pendingReviewStore = InMemoryPendingSyncReviewStore(),
        )
    }
}

private class RecordingMetadataDao(
    initial: List<S3SyncMetadataEntity> = emptyList(),
) : S3SyncMetadataDao {
    private val entities = linkedMapOf<String, S3SyncMetadataEntity>()

    var clearAllCalls: Int = 0
        private set
    var replaceAllCalls: Int = 0
        private set

    private val upsertBatches = mutableListOf<List<S3SyncMetadataEntity>>()
    private val deletedRelativePaths = mutableListOf<String>()
    private val deletedPathBatches = mutableListOf<List<String>>()

    init {
        initial.forEach { entity ->
            entities[entity.relativePath] = entity
        }
    }

    override suspend fun getAll(): List<S3SyncMetadataEntity> = entities.values.toList()

    override suspend fun getAllPlannerMetadataSnapshots(): List<S3SyncPlannerMetadataSnapshot> =
        entities.values.map { entity ->
            S3SyncPlannerMetadataSnapshot(
                relativePath = entity.relativePath,
                remotePath = entity.remotePath,
                etag = entity.etag,
                remoteLastModified = entity.remoteLastModified,
                localLastModified = entity.localLastModified,
                lastSyncedAt = entity.lastSyncedAt,
                lastResolvedDirection = entity.lastResolvedDirection,
                lastResolvedReason = entity.lastResolvedReason,
            )
        }

    override suspend fun getAllRemoteMetadataSnapshots(): List<S3SyncRemoteMetadataSnapshot> =
        entities.values.map { entity ->
            S3SyncRemoteMetadataSnapshot(
                relativePath = entity.relativePath,
                remotePath = entity.remotePath,
                etag = entity.etag,
                remoteLastModified = entity.remoteLastModified,
            )
        }

    override suspend fun getByRelativePaths(relativePaths: List<String>): List<S3SyncMetadataEntity> =
        relativePaths.mapNotNull(entities::get)

    override suspend fun upsertAll(entities: List<S3SyncMetadataEntity>) {
        upsertBatches += entities
        entities.forEach { entity ->
            this.entities[entity.relativePath] = entity
        }
    }

    override suspend fun deleteByRelativePath(relativePath: String) {
        deletedRelativePaths += relativePath
        entities.remove(relativePath)
    }

    override suspend fun deleteByRelativePaths(relativePaths: List<String>) {
        deletedRelativePaths += relativePaths
        relativePaths.forEach(entities::remove)
        deletedPathBatches += relativePaths
    }

    override suspend fun clearAll() {
        clearAllCalls += 1
        entities.clear()
    }

    override suspend fun replaceAll(entities: List<S3SyncMetadataEntity>) {
        replaceAllCalls += 1
        clearAll()
        upsertAll(entities)
    }

    fun upsertedPaths(): List<String> = upsertBatches.flatten().map(S3SyncMetadataEntity::relativePath)

    fun deletedPathBatches(): List<List<String>> = deletedPathBatches.toList()

    fun deletedSinglePaths(): List<String> = deletedRelativePaths.filterNot { path ->
        deletedPathBatches.any { batch -> path in batch }
    }
}

private class ParallelUploadProbeClient : LomoS3Client {
    private val inFlightUploads = AtomicInteger(0)
    private val maxConcurrentUploadsValue = AtomicInteger(0)
    private val release = CompletableDeferred<Unit>()

    var maxConcurrentUploads: Int = 0
        private set

    override suspend fun verifyAccess(prefix: String) = Unit

    override suspend fun list(
        prefix: String,
        maxKeys: Int?,
    ): List<S3RemoteObject> = emptyList()

    override suspend fun getSmallObject(key: String): S3SmallObjectPayload {
        error("getObject should not be used in upload-only test")
    }

    override suspend fun putSmallObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
        ifMatch: String?,
        ifNoneMatch: String?,
    ): S3PutObjectResult {
        val concurrent = inFlightUploads.incrementAndGet()
        maxConcurrentUploadsValue.updateAndGet { previous -> max(previous, concurrent) }
        maxConcurrentUploads = maxConcurrentUploadsValue.get()
        if (concurrent >= 2 && !release.isCompleted) {
            release.complete(Unit)
        }
        try {
            kotlinx.coroutines.withTimeoutOrNull(30_000) {
                release.await()
            }
            kotlinx.coroutines.delay(50)
            return S3PutObjectResult(eTag = "etag-$key")
        } finally {
            inFlightUploads.decrementAndGet()
        }
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

private class ParallelListingProbeClient(
    private val probe: ConcurrentScanProbe,
) : LomoS3Client {
    override suspend fun verifyAccess(prefix: String) = Unit

    override suspend fun list(
        prefix: String,
        maxKeys: Int?,
    ): List<S3RemoteObject> {
        probe.track()
        return listOf(
            S3RemoteObject(
                key = "lomo/memo/note.md",
                eTag = "etag-note",
                lastModified = 10L,
                metadata = emptyMap(),
            ),
        )
    }

    override suspend fun getSmallObject(key: String): S3SmallObjectPayload {
        error("getObject should not be used in parallel listing test")
    }

    override suspend fun putSmallObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
        ifMatch: String?,
        ifNoneMatch: String?,
    ): S3PutObjectResult {
        error("putObject should not be used in parallel listing test")
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

private class ParallelPagedListingProbeClient(
    private val probe: ConcurrentScanProbe,
) : LomoS3Client {
    private val inFlightPages = AtomicInteger(0)
    private val maxConcurrentPagesValue = AtomicInteger(0)

    val listedPrefixes = Collections.synchronizedList(mutableListOf<String>())
    var maxConcurrentPages: Int = 0
        private set

    override suspend fun verifyAccess(prefix: String) = Unit

    override suspend fun list(
        prefix: String,
        maxKeys: Int?,
    ): List<S3RemoteObject> {
        error("full remote scan should use paged shard listing")
    }

    override suspend fun listPage(
        prefix: String,
        continuationToken: String?,
        maxKeys: Int,
    ): com.lomo.data.s3.S3RemoteListPage {
        check(continuationToken == null)
        listedPrefixes += prefix
        val concurrent = inFlightPages.incrementAndGet()
        maxConcurrentPagesValue.updateAndGet { previous -> max(previous, concurrent) }
        maxConcurrentPages = maxConcurrentPagesValue.get()
        try {
            probe.track()
            return com.lomo.data.s3.S3RemoteListPage(objects = emptyList(), nextContinuationToken = null)
        } finally {
            inFlightPages.decrementAndGet()
        }
    }

    override suspend fun getSmallObject(key: String): S3SmallObjectPayload {
        error("getObject should not be used in paged listing test")
    }

    override suspend fun putSmallObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
        ifMatch: String?,
        ifNoneMatch: String?,
    ): S3PutObjectResult {
        error("putObject should not be used in paged listing test")
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

private class DownloadOnlyClient(
    private val remoteObjects: List<S3RemoteObject>,
    private val payloads: Map<String, S3SmallObjectPayload>,
) : LomoS3Client {
    override suspend fun verifyAccess(prefix: String) = Unit

    override suspend fun list(
        prefix: String,
        maxKeys: Int?,
    ): List<S3RemoteObject> = remoteObjects

    override suspend fun getSmallObject(key: String): S3SmallObjectPayload = requireNotNull(payloads[key])

    override suspend fun putSmallObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
        ifMatch: String?,
        ifNoneMatch: String?,
    ): S3PutObjectResult {
        error("putObject should not be used in download-only test")
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

private class StaticListingClient(
    private val remoteObjects: List<S3RemoteObject>,
    private val uploadedEtags: Map<String, String>,
) : LomoS3Client {
    override suspend fun verifyAccess(prefix: String) = Unit

    override suspend fun list(
        prefix: String,
        maxKeys: Int?,
    ): List<S3RemoteObject> = remoteObjects

    override suspend fun getSmallObject(key: String): S3SmallObjectPayload {
        error("getObject should not be used in incremental upload test")
    }

    override suspend fun putSmallObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
        ifMatch: String?,
        ifNoneMatch: String?,
    ): S3PutObjectResult = S3PutObjectResult(eTag = requireNotNull(uploadedEtags[key]))

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

private class ConcurrentScanProbe(private val expected: Int = 1) {
    private val inFlight = AtomicInteger(0)
    private val maxConcurrentValue = AtomicInteger(0)
    private val gate = CompletableDeferred<Unit>()

    var maxConcurrent: Int = 0
        private set

    suspend fun track() {
        val concurrent = inFlight.incrementAndGet()
        maxConcurrentValue.updateAndGet { previous -> max(previous, concurrent) }
        maxConcurrent = maxConcurrentValue.get()
        if (concurrent >= expected) {
            gate.complete(Unit)
        }
        try {
            kotlinx.coroutines.withTimeoutOrNull(30_000) {
                gate.await()
            }
        } finally {
            inFlight.decrementAndGet()
        }
    }
}

private fun staleMetadata(path: String) =
    S3SyncMetadataEntity(
        relativePath = path,
        remotePath = path,
        etag = "stale",
        remoteLastModified = 5L,
        localLastModified = 5L,
        lastSyncedAt = 5L,
        lastResolvedDirection = "NONE",
        lastResolvedReason = "EXPIRED",
    )

private fun <T> countingFlow(value: T, counter: AtomicInteger): Flow<T> =
    flow {
        counter.incrementAndGet()
        emit(value)
    }
