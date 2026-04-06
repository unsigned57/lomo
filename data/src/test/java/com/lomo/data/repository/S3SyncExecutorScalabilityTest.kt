package com.lomo.data.repository

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
import com.lomo.data.s3.S3RemoteObjectPayload
import com.lomo.data.source.FileMetadata
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.Collections
import kotlin.math.max
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/*
 * Test Contract:
 * - Unit under test: S3SyncExecutor
 * - Behavior focus: preparation should overlap local, remote, and metadata discovery; multi-action sync should overlap independent uploads; metadata persistence should stay incremental and batch stale-row cleanup; and legacy attachment downloads should reuse one resolved local sync mode without extra media rescans.
 * - Observable outcomes: peak concurrent preparation and upload work, returned sync outcomes, metadata DAO clear/replace usage, stale metadata delete batching, metadata upsert scope, local media listing count, and local sync configuration collection count.
 * - Red phase: Fails before the fix because metadata loading waits for local/remote scans, S3 actions run strictly serially, stale metadata cleanup deletes one row at a time, local sync mode is re-resolved for each action, metadata persistence replaces the full table, and legacy direct-root attachment downloads trigger a second full media scan.
 * - Excludes: AWS SDK transport correctness, Room generated DAO code, WorkManager scheduling, and UI rendering.
 */
class S3SyncExecutorScalabilityTest {
    @MockK(relaxed = true)
    private lateinit var dataStore: LomoDataStore

    @MockK(relaxed = true)
    private lateinit var credentialStore: S3CredentialStore

    @MockK(relaxed = true)
    private lateinit var markdownStorageDataSource: MarkdownStorageDataSource

    @MockK(relaxed = true)
    private lateinit var localMediaSyncStore: LocalMediaSyncStore

    @MockK(relaxed = true)
    private lateinit var memoSynchronizer: MemoSynchronizer

    @Before
    fun setUp() {
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
        coEvery { markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, any()) } answers {
            "# ${secondArg<String>().removeSuffix(".md")}"
        }
        coEvery { localMediaSyncStore.listFiles(any()) } returns emptyMap()
    }

    @Test
    fun `performSync overlaps independent uploads within one sync pass`() =
        runBlocking {
            val client = ParallelUploadProbeClient()
            val metadataDao = RecordingMetadataDao()
            coEvery { markdownStorageDataSource.listMetadataIn(MemoDirectoryType.MAIN) } returns
                listOf(
                    FileMetadata(filename = "first.md", lastModified = 10L),
                    FileMetadata(filename = "second.md", lastModified = 20L),
                )

            val executor = createExecutor(client = client, metadataDao = metadataDao)

            val result = executor.performSync()

            val success = result as S3SyncResult.Success
            assertEquals("S3 sync completed", success.message)
            assertEquals(
                listOf(
                    S3SyncDirection.UPLOAD to S3SyncReason.LOCAL_ONLY,
                    S3SyncDirection.UPLOAD to S3SyncReason.LOCAL_ONLY,
                ),
                success.outcomes.map { it.direction to it.reason },
            )
            assertTrue(
                "Expected at least 2 concurrent uploads but saw ${client.maxConcurrentUploads}",
                client.maxConcurrentUploads >= 2,
            )
        }

    @Test
    fun `performSync overlaps local scan and remote listing during preparation`() =
        runBlocking {
            val probe = ConcurrentScanProbe()
            val client = ParallelListingProbeClient(probe)
            val metadataDao = RecordingMetadataDao()
            coEvery { markdownStorageDataSource.listMetadataIn(MemoDirectoryType.MAIN) } coAnswers {
                probe.track()
                listOf(FileMetadata(filename = "note.md", lastModified = 10L))
            }

            val executor = createExecutor(client = client, metadataDao = metadataDao)

            val result = executor.performSync()

            assertTrue(result is S3SyncResult.Conflict)
            assertTrue(
                "Expected local scan and remote listing to overlap but saw ${probe.maxConcurrent}",
                probe.maxConcurrent >= 2,
            )
        }

    @Test
    fun `performSync overlaps metadata load with local and remote listing during preparation`() =
        runBlocking {
            val probe = ConcurrentScanProbe()
            val client = ParallelListingProbeClient(probe)
            val metadataDao = mockk<S3SyncMetadataDao>(relaxed = true)
            coEvery { metadataDao.getAll() } coAnswers {
                probe.track()
                emptyList()
            }
            coEvery { markdownStorageDataSource.listMetadataIn(MemoDirectoryType.MAIN) } coAnswers {
                probe.track()
                listOf(FileMetadata(filename = "note.md", lastModified = 10L))
            }

            val executor = createExecutor(client = client, metadataDao = metadataDao)

            val result = executor.performSync()

            assertTrue(result is S3SyncResult.Conflict)
            assertTrue(
                "Expected local scan, remote listing, and metadata load to overlap but saw ${probe.maxConcurrent}",
                probe.maxConcurrent >= 3,
            )
        }

    @Test
    fun `performSync overlaps full remote shard listings during manifest-free snapshot build`() =
        runBlocking {
            val probe = ConcurrentScanProbe()
            val client = ParallelPagedListingProbeClient(probe)
            val metadataDao = RecordingMetadataDao()
            coEvery { markdownStorageDataSource.listMetadataIn(MemoDirectoryType.MAIN) } returns emptyList()

            val executor = createExecutor(client = client, metadataDao = metadataDao)

            val result = executor.performSync()

            val success = result as S3SyncResult.Success
            assertEquals("S3 already up to date", success.message)
            assertTrue(
                "Expected full remote shard listing to overlap but saw ${client.maxConcurrentPages}",
                client.maxConcurrentPages >= 2,
            )
            assertEquals(setOf("lomo/memo/", "lomo/images/", "lomo/voice/"), client.listedPrefixes.toSet())
        }

    @Test
    fun `performSync persists only changed metadata without clearing existing rows`() =
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
            coEvery { markdownStorageDataSource.listMetadataIn(MemoDirectoryType.MAIN) } returns
                listOf(
                    FileMetadata(filename = "stable.md", lastModified = 100L),
                    FileMetadata(filename = "fresh.md", lastModified = 200L),
                )

            val executor = createExecutor(client = client, metadataDao = metadataDao)

            val result = executor.performSync()

            val success = result as S3SyncResult.Success
            assertEquals("S3 sync completed", success.message)
            assertEquals(
                listOf(S3SyncDirection.UPLOAD to S3SyncReason.LOCAL_ONLY),
                success.outcomes.map { it.direction to it.reason },
            )
            assertEquals(0, metadataDao.replaceAllCalls)
            assertEquals(0, metadataDao.clearAllCalls)
            assertEquals(listOf(freshPath), metadataDao.upsertedPaths())
        }

    @Test
    fun `performSync batches stale metadata deletions`() =
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
            coEvery { markdownStorageDataSource.listMetadataIn(MemoDirectoryType.MAIN) } returns emptyList()

            val executor = createExecutor(client = client, metadataDao = metadataDao)

            val result = executor.performSync()

            val success = result as S3SyncResult.Success
            assertEquals("S3 already up to date", success.message)
            assertEquals(listOf(listOf(secondStalePath, firstStalePath)), metadataDao.deletedPathBatches())
            assertEquals(emptyList<String>(), metadataDao.deletedSinglePaths())
        }

    @Test
    fun `performSync does not rescan local files after download updates local state`() =
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
                                S3RemoteObjectPayload(
                                    key = memoRemotePath,
                                    eTag = "etag-note",
                                    lastModified = 30L,
                                    metadata = emptyMap(),
                                    bytes = "# note".toByteArray(),
                                ),
                        ),
                )
            coEvery { markdownStorageDataSource.listMetadataIn(MemoDirectoryType.MAIN) } returns emptyList()
            coEvery {
                markdownStorageDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = "note.md",
                    content = "# note",
                    append = false,
                    uri = null,
                )
            } returns null
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(
                    MemoDirectoryType.MAIN,
                    "note.md",
                )
            } returns com.lomo.data.source.FileMetadata(filename = "note.md", lastModified = 30L)

            val executor = createExecutor(client = client, metadataDao = metadataDao)

            val result = executor.performSync()

            val success = result as S3SyncResult.Success
            assertEquals("S3 sync completed", success.message)
            assertEquals(
                listOf(S3SyncDirection.DOWNLOAD to S3SyncReason.REMOTE_ONLY),
                success.outcomes.map { it.direction to it.reason },
            )
            io.mockk.coVerify(exactly = 1) { markdownStorageDataSource.listMetadataIn(MemoDirectoryType.MAIN) }
        }

    @Test
    fun `performSync does not rescan legacy media store after direct-root attachment download`() =
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
                                    S3RemoteObjectPayload(
                                        key = imagePath,
                                        eTag = "etag-image",
                                        lastModified = 40L,
                                        metadata = emptyMap(),
                                        bytes = byteArrayOf(1, 2, 3),
                                    ),
                            ),
                    )
                coEvery { markdownStorageDataSource.listMetadataIn(MemoDirectoryType.MAIN) } returns emptyList()
                coEvery { localMediaSyncStore.writeBytes(imagePath, any(), any()) } coAnswers {
                    imageRoot.mkdirs()
                    java.io.File(imageRoot, "cover.png").writeBytes(secondArg())
                }

                val executor = createExecutor(client = client, metadataDao = metadataDao)

                val result = executor.performSync()

                val success = result as S3SyncResult.Success
                assertEquals("S3 sync completed", success.message)
                assertEquals(
                    listOf(S3SyncDirection.DOWNLOAD to S3SyncReason.REMOTE_ONLY),
                    success.outcomes.map { it.direction to it.reason },
                )
                coVerify(exactly = 1) { localMediaSyncStore.listFiles(any()) }
            } finally {
                mediaRoot.deleteRecursively()
            }
        }

    @Test
    fun `performSync reuses resolved legacy sync mode during attachment download`() =
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
                                    S3RemoteObjectPayload(
                                        key = imagePath,
                                        eTag = "etag-image",
                                        lastModified = 40L,
                                        metadata = emptyMap(),
                                        bytes = byteArrayOf(1, 2, 3),
                                    ),
                            ),
                    )
                coEvery { markdownStorageDataSource.listMetadataIn(MemoDirectoryType.MAIN) } returns emptyList()
                coEvery { localMediaSyncStore.writeBytes(imagePath, any(), any()) } coAnswers {
                    imageRoot.mkdirs()
                    java.io.File(imageRoot, "cover.png").writeBytes(secondArg())
                }

                val executor = createExecutor(client = client, metadataDao = metadataDao)

                val result = executor.performSync()

                val success = result as S3SyncResult.Success
                assertEquals("S3 sync completed", success.message)
                assertEquals(1, s3LocalSyncDirectoryCollections.get())
                assertEquals(2, rootDirectoryCollections.get())
                assertEquals(2, rootUriCollections.get())
                assertEquals(2, imageDirectoryCollections.get())
                assertEquals(2, imageUriCollections.get())
                assertEquals(2, voiceDirectoryCollections.get())
                assertEquals(2, voiceUriCollections.get())
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
            )
        val encodingSupport = S3SyncEncodingSupport()
        val fileBridge = S3SyncFileBridge(runtime, encodingSupport)
        return S3SyncExecutor(
            runtime = runtime,
            support = S3SyncRepositorySupport(runtime),
            encodingSupport = encodingSupport,
            fileBridge = fileBridge,
            actionApplier = S3SyncActionApplier(runtime, encodingSupport, fileBridge),
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

    override suspend fun getObject(key: String): S3RemoteObjectPayload {
        error("getObject should not be used in upload-only test")
    }

    override suspend fun putObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
    ): S3PutObjectResult {
        val concurrent = inFlightUploads.incrementAndGet()
        maxConcurrentUploadsValue.updateAndGet { previous -> max(previous, concurrent) }
        maxConcurrentUploads = maxConcurrentUploadsValue.get()
        if (concurrent >= 2 && !release.isCompleted) {
            release.complete(Unit)
        }
        try {
            kotlinx.coroutines.withTimeoutOrNull(2_000) {
                release.await()
            }
            kotlinx.coroutines.delay(50)
            return S3PutObjectResult(eTag = "etag-$key")
        } finally {
            inFlightUploads.decrementAndGet()
        }
    }

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

    override suspend fun getObject(key: String): S3RemoteObjectPayload {
        error("getObject should not be used in parallel listing test")
    }

    override suspend fun putObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
    ): S3PutObjectResult {
        error("putObject should not be used in parallel listing test")
    }

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

    override suspend fun getObject(key: String): S3RemoteObjectPayload {
        error("getObject should not be used in paged listing test")
    }

    override suspend fun putObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
    ): S3PutObjectResult {
        error("putObject should not be used in paged listing test")
    }

    override suspend fun deleteObject(key: String) = Unit

    override fun close() = Unit
}

private class DownloadOnlyClient(
    private val remoteObjects: List<S3RemoteObject>,
    private val payloads: Map<String, S3RemoteObjectPayload>,
) : LomoS3Client {
    override suspend fun verifyAccess(prefix: String) = Unit

    override suspend fun list(
        prefix: String,
        maxKeys: Int?,
    ): List<S3RemoteObject> = remoteObjects

    override suspend fun getObject(key: String): S3RemoteObjectPayload = requireNotNull(payloads[key])

    override suspend fun putObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
    ): S3PutObjectResult {
        error("putObject should not be used in download-only test")
    }

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

    override suspend fun getObject(key: String): S3RemoteObjectPayload {
        error("getObject should not be used in incremental upload test")
    }

    override suspend fun putObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
    ): S3PutObjectResult = S3PutObjectResult(eTag = requireNotNull(uploadedEtags[key]))

    override suspend fun deleteObject(key: String) = Unit

    override fun close() = Unit
}

private class ConcurrentScanProbe {
    private val inFlight = AtomicInteger(0)
    private val maxConcurrentValue = AtomicInteger(0)

    var maxConcurrent: Int = 0
        private set

    suspend fun track() {
        val concurrent = inFlight.incrementAndGet()
        maxConcurrentValue.updateAndGet { previous -> max(previous, concurrent) }
        maxConcurrent = maxConcurrentValue.get()
        try {
            kotlinx.coroutines.delay(150)
        } finally {
            inFlight.decrementAndGet()
        }
    }
}

private fun staleMetadata(relativePath: String) =
    S3SyncMetadataEntity(
        relativePath = relativePath,
        remotePath = relativePath,
        etag = "etag-$relativePath",
        remoteLastModified = 10L,
        localLastModified = 10L,
        lastSyncedAt = 10L,
        lastResolvedDirection = "NONE",
        lastResolvedReason = "UNCHANGED",
    )

private fun <T> countingFlow(
    value: T,
    counter: AtomicInteger,
): Flow<T> =
    flow {
        counter.incrementAndGet()
        emit(value)
    }
