package com.lomo.data.repository

import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.dao.S3SyncPlannerMetadataSnapshot
import com.lomo.data.local.dao.S3SyncRemoteMetadataSnapshot
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.local.entity.S3SyncMetadataEntity
import com.lomo.data.s3.LomoS3Client
import com.lomo.data.s3.LomoS3ClientFactory
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.s3.S3RemoteObject
import com.lomo.data.s3.S3RemoteObjectPayload
import com.lomo.data.source.FileMetadata
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.domain.model.S3SyncStatus
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/*
 * Test Contract:
 * - Unit under test: S3SyncStatusTester
 * - Behavior focus: manifest-free status checks should reuse a fresh local remote index, derive pending local changes from the journal without whole-bucket probes, and reconcile with a full remote listing when the cached index is stale.
 * - Observable outcomes: returned S3SyncStatus, remote list/head invocation counts, and local metadata lookup scope.
 * - Red phase: Fails before the fix because status still depends on manifest probing and cannot answer fast-path status queries from the cached remote index alone.
 * - Excludes: AWS transport behavior, metadata persistence mutations, and UI rendering.
 */
class S3SyncIncrementalStatusTesterTest {
    @MockK(relaxed = true)
    private lateinit var dataStore: LomoDataStore

    @MockK(relaxed = true)
    private lateinit var credentialStore: S3CredentialStore

    @MockK(relaxed = true)
    private lateinit var clientFactory: LomoS3ClientFactory

    @MockK(relaxed = true)
    private lateinit var markdownStorageDataSource: MarkdownStorageDataSource

    @MockK(relaxed = true)
    private lateinit var localMediaSyncStore: LocalMediaSyncStore

    @MockK(relaxed = true)
    private lateinit var memoSynchronizer: MemoSynchronizer

    private lateinit var protocolStateStore: InMemoryS3SyncProtocolStateStore
    private lateinit var journalStore: InMemoryS3LocalChangeJournalStore

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
        every { dataStore.s3LastSyncTime } returns flowOf(123L)
        every { credentialStore.getAccessKeyId() } returns "access"
        every { credentialStore.getSecretAccessKey() } returns "secret"
        every { credentialStore.getSessionToken() } returns null
        every { credentialStore.getEncryptionPassword() } returns null
        every { credentialStore.getEncryptionPassword2() } returns null
        coEvery { localMediaSyncStore.listFiles(any()) } returns emptyMap()

        protocolStateStore = InMemoryS3SyncProtocolStateStore()
        journalStore = InMemoryS3LocalChangeJournalStore()
    }

    @Test
    fun `getStatus answers from fresh cached remote index without remote probes`() =
        runTest {
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastSuccessfulSyncAt = System.currentTimeMillis(),
                    lastFullRemoteScanAt = System.currentTimeMillis(),
                    indexedLocalFileCount = 2,
                    indexedRemoteFileCount = 3,
                ),
            )
            val client =
                ProbeStatusS3Client(
                    onList = { throw AssertionError("fresh status should not list remote objects") },
                    onGetObjectMetadata = {
                        throw AssertionError("fresh status should not head manifest or remote objects")
                    },
                )
            val tester = createTester(client = client, metadataDao = RecordingStatusMetadataDao())

            val status = tester.getStatus()

            assertEquals(
                S3SyncStatus(remoteFileCount = 3, localFileCount = 2, pendingChanges = 0, lastSyncTime = 123L),
                status,
            )
            assertEquals(0, client.listCalls)
            assertEquals(0, client.headCalls)
        }

    @Test
    fun `getStatus derives pending journal changes without remote probes when cache is fresh`() =
        runTest {
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastSuccessfulSyncAt = System.currentTimeMillis(),
                    lastFullRemoteScanAt = System.currentTimeMillis(),
                    indexedLocalFileCount = 1,
                    indexedRemoteFileCount = 0,
                ),
            )
            journalStore.upsert(
                S3LocalChangeJournalEntry(
                    id = "MEMO:note.md",
                    kind = S3LocalChangeKind.MEMO,
                    filename = "note.md",
                    changeType = S3LocalChangeType.UPSERT,
                    updatedAt = 50L,
                ),
            )
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "note.md")
            } returns FileMetadata(filename = "note.md", lastModified = 50L)
            val client =
                ProbeStatusS3Client(
                    onList = { throw AssertionError("fresh pending status should not list remote objects") },
                    onGetObjectMetadata = {
                        throw AssertionError("fresh pending status should not head manifest or remote objects")
                    },
                )
            val tester = createTester(client = client, metadataDao = RecordingStatusMetadataDao())

            val status = tester.getStatus()

            assertEquals(
                S3SyncStatus(remoteFileCount = 0, localFileCount = 1, pendingChanges = 1, lastSyncTime = 123L),
                status,
            )
            assertEquals(0, client.listCalls)
            assertEquals(0, client.headCalls)
        }

    @Test
    fun `getStatus reconciles with full remote listing when cached index is stale`() =
        runTest {
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastSuccessfulSyncAt = 1L,
                    lastFullRemoteScanAt = 1L,
                    indexedLocalFileCount = 0,
                    indexedRemoteFileCount = 0,
                ),
            )
            coEvery { markdownStorageDataSource.listMetadataIn(MemoDirectoryType.MAIN) } returns emptyList()
            val client =
                ProbeStatusS3Client(
                    onList = {
                        listOf(
                            S3RemoteObject(
                                key = "lomo/memo/remote.md",
                                eTag = "etag-remote",
                                lastModified = 40L,
                                metadata = emptyMap(),
                            ),
                        )
                    },
                    onGetObjectMetadata = { null },
                )
            val tester = createTester(client = client, metadataDao = RecordingStatusMetadataDao())

            val status = tester.getStatus()

            assertEquals(
                S3SyncStatus(remoteFileCount = 1, localFileCount = 0, pendingChanges = 1, lastSyncTime = 123L),
                status,
            )
            assertEquals(3, client.listCalls)
            assertEquals(0, client.headCalls)
        }

    private fun createTester(
        client: ProbeStatusS3Client,
        metadataDao: RecordingStatusMetadataDao,
    ): S3SyncStatusTester {
        every { clientFactory.create(any()) } returns client
        val runtime =
            S3SyncRepositoryContext(
                dataStore = dataStore,
                credentialStore = credentialStore,
                clientFactory = clientFactory,
                markdownStorageDataSource = markdownStorageDataSource,
                localMediaSyncStore = localMediaSyncStore,
                metadataDao = metadataDao,
                memoSynchronizer = memoSynchronizer,
                planner = S3SyncPlanner(timestampToleranceMs = 0L),
                stateHolder = S3SyncStateHolder(),
            )
        val encodingSupport = S3SyncEncodingSupport()
        return S3SyncStatusTester(
            runtime = runtime,
            support = S3SyncRepositorySupport(runtime),
            encodingSupport = encodingSupport,
            fileBridge = S3SyncFileBridge(runtime, encodingSupport),
            protocolStateStore = protocolStateStore,
            localChangeJournalStore = journalStore,
        )
    }
}

private class RecordingStatusMetadataDao(
    initial: List<S3SyncMetadataEntity> = emptyList(),
) : S3SyncMetadataDao {
    private val entries = linkedMapOf<String, S3SyncMetadataEntity>()

    init {
        initial.forEach { entity -> entries[entity.relativePath] = entity }
    }

    override suspend fun getAll(): List<S3SyncMetadataEntity> = entries.values.toList()

    override suspend fun getAllPlannerMetadataSnapshots(): List<S3SyncPlannerMetadataSnapshot> =
        entries.values.map { entity ->
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
        entries.values.map { entity ->
            S3SyncRemoteMetadataSnapshot(
                relativePath = entity.relativePath,
                remotePath = entity.remotePath,
                etag = entity.etag,
                remoteLastModified = entity.remoteLastModified,
            )
        }

    override suspend fun getByRelativePaths(relativePaths: List<String>): List<S3SyncMetadataEntity> =
        relativePaths.mapNotNull(entries::get)

    override suspend fun upsertAll(entities: List<S3SyncMetadataEntity>) = Unit

    override suspend fun deleteByRelativePath(relativePath: String) = Unit

    override suspend fun deleteByRelativePaths(relativePaths: List<String>) = Unit

    override suspend fun clearAll() = Unit
}

private class ProbeStatusS3Client(
    private val onList: suspend () -> List<S3RemoteObject> = { emptyList() },
    private val onGetObjectMetadata: suspend (String) -> S3RemoteObject? = {
        throw AssertionError("Unexpected headObject for $it")
    },
) : LomoS3Client {
    private val listCallCounter = AtomicInteger(0)
    private val headCallCounter = AtomicInteger(0)

    val listCalls: Int
        get() = listCallCounter.get()

    val headCalls: Int
        get() = headCallCounter.get()

    override suspend fun verifyAccess(prefix: String) = Unit

    override suspend fun getObjectMetadata(key: String): S3RemoteObject? {
        headCallCounter.incrementAndGet()
        return onGetObjectMetadata(key)
    }

    override suspend fun list(
        prefix: String,
        maxKeys: Int?,
    ): List<S3RemoteObject> {
        listCallCounter.incrementAndGet()
        return onList()
    }

    override suspend fun getObject(key: String): S3RemoteObjectPayload {
        throw AssertionError("Status checks should not download remote objects: $key")
    }

    override suspend fun putObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
    ) = throw AssertionError("Status checks should not upload remote objects: $key")

    override suspend fun deleteObject(key: String) =
        throw AssertionError("Status checks should not delete remote objects: $key")

    override fun close() = Unit
}
