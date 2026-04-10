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
import com.lomo.data.s3.S3RemoteListPage
import com.lomo.data.s3.S3RemoteObject
import com.lomo.data.s3.S3RemoteObjectPayload
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncReason
import com.lomo.domain.model.S3SyncResult
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/*
 * Test Contract:
 * - Unit under test: S3SyncExecutor
 * - Behavior focus: initial S3 sync should fast-classify overlapping files so empty-local snapshots download directly, equivalent local/remote files avoid redundant transfer and seed metadata, clear one-sided changes auto-sync, and irreconcilable overlaps stay in preview conflict.
 * - Observable outcomes: returned S3SyncResult outcomes, remote get/put/delete/head invocation counts, local file contents after sync, and metadata rows persisted for initial equivalence.
 * - Red phase: Fails before the fix because initial sync treats every overlapping path without metadata as a conflict, so identical files do not seed metadata, clear newer-side changes do not auto-sync, and the initial overlap path never reaches the optimized fast path.
 * - Excludes: AWS SDK transport internals, Room generated code, WorkManager scheduling, and UI rendering.
 */
class S3InitialSyncOptimizationTest {
    @get:Rule val tempFolder = TemporaryFolder()

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

    private lateinit var vaultRoot: File

    @Before
    fun setUp() {
        MockKAnnotations.init(this)

        vaultRoot = tempFolder.newFolder("vault-root")
        File(vaultRoot, "memo").mkdirs()
        File(vaultRoot, "images").mkdirs()
        File(vaultRoot, "voice").mkdirs()

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
        every { dataStore.s3LocalSyncDirectory } returns flowOf(vaultRoot.absolutePath)
        every { dataStore.rootDirectory } returns flowOf(File(vaultRoot, "memo").absolutePath)
        every { dataStore.rootUri } returns flowOf(null)
        every { dataStore.imageDirectory } returns flowOf(File(vaultRoot, "images").absolutePath)
        every { dataStore.imageUri } returns flowOf(null)
        every { dataStore.voiceDirectory } returns flowOf(File(vaultRoot, "voice").absolutePath)
        every { dataStore.voiceUri } returns flowOf(null)
        every { credentialStore.getAccessKeyId() } returns "access"
        every { credentialStore.getSecretAccessKey() } returns "secret"
        every { credentialStore.getSessionToken() } returns null
        every { credentialStore.getEncryptionPassword() } returns null
        every { credentialStore.getEncryptionPassword2() } returns null

        coEvery { dataStore.updateS3LastSyncTime(any()) } returns Unit
        coEvery { localMediaSyncStore.listFiles(any()) } returns emptyMap()
        coEvery { memoSynchronizer.refreshImportedSync(any()) } returns Unit
        coEvery { memoSynchronizer.refresh() } returns Unit
    }

    @Test
    fun `performSync downloads remote files immediately when local vault is empty`() =
        runTest {
            val remoteBytes = "# remote".toByteArray(StandardCharsets.UTF_8)
            val client =
                RecordingInitialSyncClient(
                    remoteFixtures =
                        listOf(
                            remoteFixture(
                                key = "memo/remote.md",
                                bytes = remoteBytes,
                                lastModified = 100L,
                            ),
                        ),
                )

            val result = createExecutor(client = client, metadataDao = RecordingInitialMetadataDao()).performSync()

            assertTrue("Expected success, got $result", result is S3SyncResult.Success)
            val success = result as S3SyncResult.Success
            assertEquals(
                listOf(S3SyncDirection.DOWNLOAD to S3SyncReason.REMOTE_ONLY),
                success.outcomes.map { it.direction to it.reason },
            )
            assertEquals("# remote", File(vaultRoot, "memo/remote.md").readText())
            assertEquals(1, client.getObjectCalls)
            assertEquals(0, client.putObjectCalls)
            assertEquals(0, client.headCalls)
        }

    @Test
    fun `performSync treats initial overlapping identical memo as up to date and seeds metadata`() =
        runTest {
            val sharedBytes = "# same".toByteArray(StandardCharsets.UTF_8)
            val localFile = writeLocalFile("memo/note.md", sharedBytes, lastModified = 120L)
            val metadataDao = RecordingInitialMetadataDao()
            val client =
                RecordingInitialSyncClient(
                    remoteFixtures =
                        listOf(
                            remoteFixture(
                                key = "memo/note.md",
                                bytes = sharedBytes,
                                lastModified = 100L,
                            ),
                        ),
                )

            val result = createExecutor(client = client, metadataDao = metadataDao).performSync()

            assertEquals(
                S3SyncResult.Success(message = "S3 already up to date", outcomes = emptyList()),
                result,
            )
            assertEquals("# same", localFile.readText())
            assertEquals(0, client.getObjectCalls)
            assertEquals(0, client.putObjectCalls)
            assertEquals(0, client.deleteObjectCalls)
            assertEquals(0, client.headCalls)
            assertEquals(listOf("memo/note.md"), metadataDao.paths())
            val persisted = metadataDao.getAll().single()
            assertEquals(sharedBytes.size.toLong(), persisted.localSize)
            assertEquals(sharedBytes.size.toLong(), persisted.remoteSize)
            assertEquals(md5Hex(sharedBytes), persisted.localFingerprint)
        }

    @Test
    fun `performSync uploads clearly newer local overlap instead of raising initial conflict`() =
        runTest {
            val localBytes = "# local newer".toByteArray(StandardCharsets.UTF_8)
            writeLocalFile("memo/note.md", localBytes, lastModified = 200L)
            val remoteBytes = "# old".toByteArray(StandardCharsets.UTF_8)
            val client =
                RecordingInitialSyncClient(
                    remoteFixtures =
                        listOf(
                            remoteFixture(
                                key = "memo/note.md",
                                bytes = remoteBytes,
                                lastModified = 100L,
                            ),
                        ),
                )

            val result = createExecutor(client = client, metadataDao = RecordingInitialMetadataDao()).performSync()

            assertTrue("Expected success, got $result", result is S3SyncResult.Success)
            val success = result as S3SyncResult.Success
            assertEquals(
                listOf(S3SyncDirection.UPLOAD to S3SyncReason.LOCAL_ONLY),
                success.outcomes.map { it.direction to it.reason },
            )
            assertEquals(1, client.putObjectCalls)
            assertEquals(listOf("memo/note.md"), client.putKeys)
            assertEquals(0, client.getObjectCalls)
        }

    @Test
    fun `performSync downloads clearly newer remote overlap instead of raising initial conflict`() =
        runTest {
            writeLocalFile(
                relativePath = "memo/note.md",
                bytes = "# old".toByteArray(StandardCharsets.UTF_8),
                lastModified = 100L,
            )
            val remoteBytes = "# remote newer".toByteArray(StandardCharsets.UTF_8)
            val client =
                RecordingInitialSyncClient(
                    remoteFixtures =
                        listOf(
                            remoteFixture(
                                key = "memo/note.md",
                                bytes = remoteBytes,
                                lastModified = 200L,
                            ),
                        ),
                )

            val result = createExecutor(client = client, metadataDao = RecordingInitialMetadataDao()).performSync()

            assertTrue("Expected success, got $result", result is S3SyncResult.Success)
            val success = result as S3SyncResult.Success
            assertEquals(
                listOf(S3SyncDirection.DOWNLOAD to S3SyncReason.REMOTE_ONLY),
                success.outcomes.map { it.direction to it.reason },
            )
            assertEquals("# remote newer", File(vaultRoot, "memo/note.md").readText())
            assertEquals(1, client.getObjectCalls)
            assertEquals(0, client.putObjectCalls)
        }

    @Test
    fun `performSync keeps initial overlap in conflict when same-size content disagrees without a clear newer side`() =
        runTest {
            writeLocalFile(
                relativePath = "memo/note.md",
                bytes = "AAAA".toByteArray(StandardCharsets.UTF_8),
                lastModified = 100L,
            )
            val remoteBytes = "BBBB".toByteArray(StandardCharsets.UTF_8)
            val client =
                RecordingInitialSyncClient(
                    remoteFixtures =
                        listOf(
                            remoteFixture(
                                key = "memo/note.md",
                                bytes = remoteBytes,
                                lastModified = 100L,
                            ),
                        ),
                )

            val result = createExecutor(client = client, metadataDao = RecordingInitialMetadataDao()).performSync()

            assertTrue(result is S3SyncResult.Conflict)
        }

    @Test
    fun `performSync treats identical memo with unusable etag as up to date by reading content on demand`() =
        runTest {
            val sharedBytes = "# same".toByteArray(StandardCharsets.UTF_8)
            writeLocalFile("memo/note.md", sharedBytes, lastModified = 100L)
            val metadataDao = RecordingInitialMetadataDao()
            val client =
                RecordingInitialSyncClient(
                    remoteFixtures =
                        listOf(
                            remoteFixture(
                                key = "memo/note.md",
                                bytes = sharedBytes,
                                lastModified = 100L,
                                eTag = "multipart-2",
                            ),
                        ),
                )

            val result = createExecutor(client = client, metadataDao = metadataDao).performSync()

            assertEquals(
                S3SyncResult.Success(message = "S3 already up to date", outcomes = emptyList()),
                result,
            )
            assertEquals(1, client.getObjectCalls)
            assertEquals(0, client.putObjectCalls)
            assertEquals(listOf("memo/note.md"), metadataDao.paths())
            val persisted = metadataDao.getAll().single()
            assertEquals(sharedBytes.size.toLong(), persisted.localSize)
            assertEquals(sharedBytes.size.toLong(), persisted.remoteSize)
            assertEquals(md5Hex(sharedBytes), persisted.localFingerprint)
        }

    @Test
    fun `performSync uploads newer memo when etag is unusable but content comparison proves drift`() =
        runTest {
            writeLocalFile(
                relativePath = "memo/note.md",
                bytes = "LOCAL1".toByteArray(StandardCharsets.UTF_8),
                lastModified = 200L,
            )
            val client =
                RecordingInitialSyncClient(
                    remoteFixtures =
                        listOf(
                            remoteFixture(
                                key = "memo/note.md",
                                bytes = "REMOTE".toByteArray(StandardCharsets.UTF_8),
                                lastModified = 100L,
                                eTag = null,
                            ),
                        ),
                )

            val result = createExecutor(client = client, metadataDao = RecordingInitialMetadataDao()).performSync()

            assertTrue("Expected success, got $result", result is S3SyncResult.Success)
            val success = result as S3SyncResult.Success
            assertEquals(
                listOf(S3SyncDirection.UPLOAD to S3SyncReason.LOCAL_ONLY),
                success.outcomes.map { it.direction to it.reason },
            )
            assertEquals(1, client.getObjectCalls)
            assertEquals(1, client.putObjectCalls)
        }

    @Test
    fun `performSync avoids fetching every remote memo body for high-conflict initial preview`() =
        runTest {
            val remoteFixtures =
                (0 until 10).map { index ->
                    val filename = "memo/note-$index.md"
                    writeLocalFile(
                        relativePath = filename,
                        bytes = "L$index".padEnd(8, 'L').toByteArray(StandardCharsets.UTF_8),
                        lastModified = 100L,
                    )
                    remoteFixture(
                        key = filename,
                        bytes = "R$index".padEnd(8, 'R').toByteArray(StandardCharsets.UTF_8),
                        lastModified = 100L,
                        eTag = null,
                    )
                }
            val client = RecordingInitialSyncClient(remoteFixtures = remoteFixtures)

            val result = createExecutor(client = client, metadataDao = RecordingInitialMetadataDao()).performSync()

            assertTrue("Expected conflict, got $result", result is S3SyncResult.Conflict)
            val conflict = result as S3SyncResult.Conflict
            assertEquals(10, conflict.conflicts.files.size)
            assertTrue(conflict.conflicts.files.all { file -> file.localContent == null && file.remoteContent == null })
            assertEquals(0, client.getObjectCalls)
            assertEquals(0, client.putObjectCalls)
        }

    private fun createExecutor(
        client: RecordingInitialSyncClient,
        metadataDao: RecordingInitialMetadataDao,
    ): S3SyncExecutor {
        every { clientFactory.create(any()) } returns client
        val encodingSupport = S3SyncEncodingSupport()
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
        val fileBridge = S3SyncFileBridge(runtime, encodingSupport)
        return S3SyncExecutor(
            runtime = runtime,
            support = S3SyncRepositorySupport(runtime),
            encodingSupport = encodingSupport,
            fileBridge = fileBridge,
            actionApplier = S3SyncActionApplier(runtime, encodingSupport, fileBridge),
        )
    }

    private fun writeLocalFile(
        relativePath: String,
        bytes: ByteArray,
        lastModified: Long,
    ): File =
        File(vaultRoot, relativePath).also { file ->
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            file.setLastModified(lastModified)
        }

    private fun remoteFixture(
        key: String,
        bytes: ByteArray,
        lastModified: Long,
        eTag: String? = md5Hex(bytes),
    ): InitialRemoteFixture =
        InitialRemoteFixture(
            objectSummary =
                S3RemoteObject(
                    key = key,
                    eTag = eTag,
                    lastModified = lastModified,
                    size = bytes.size.toLong(),
                    metadata = emptyMap(),
                ),
            payload =
                S3RemoteObjectPayload(
                    key = key,
                    eTag = eTag,
                    lastModified = lastModified,
                    metadata = emptyMap(),
                    bytes = bytes,
                ),
        )

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest
            .getInstance("MD5")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private class RecordingInitialMetadataDao(
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
                localSize = entity.localSize,
                remoteSize = entity.remoteSize,
                localFingerprint = entity.localFingerprint,
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

    override suspend fun upsertAll(entities: List<S3SyncMetadataEntity>) {
        entities.forEach { entity -> entries[entity.relativePath] = entity }
    }

    override suspend fun deleteByRelativePath(relativePath: String) {
        entries.remove(relativePath)
    }

    override suspend fun deleteByRelativePaths(relativePaths: List<String>) {
        relativePaths.forEach(entries::remove)
    }

    override suspend fun clearAll() {
        entries.clear()
    }

    fun paths(): List<String> = entries.keys.toList()
}

private class RecordingInitialSyncClient(
    remoteFixtures: List<InitialRemoteFixture>,
) : LomoS3Client {
    private val objectsByKey = remoteFixtures.associate { it.objectSummary.key to it.objectSummary }.toMutableMap()
    private val payloadsByKey = remoteFixtures.associate { it.payload.key to it.payload }.toMutableMap()

    val putKeys = mutableListOf<String>()
    var getObjectCalls: Int = 0
        private set
    var putObjectCalls: Int = 0
        private set
    var deleteObjectCalls: Int = 0
        private set
    var headCalls: Int = 0
        private set

    override suspend fun verifyAccess(prefix: String) = Unit

    override suspend fun getObjectMetadata(key: String): S3RemoteObject? {
        headCalls += 1
        return objectsByKey[key]
    }

    override suspend fun list(
        prefix: String,
        maxKeys: Int?,
    ): List<S3RemoteObject> = error("list() should not be used in initial sync tests")

    override suspend fun listPage(
        prefix: String,
        continuationToken: String?,
        maxKeys: Int,
    ): S3RemoteListPage =
        S3RemoteListPage(
            objects =
                if (continuationToken == null) {
                    objectsByKey.values.filter { remote -> remote.key.startsWith(prefix) }
                } else {
                    emptyList()
                },
            nextContinuationToken = null,
        )

    override suspend fun getObject(key: String): S3RemoteObjectPayload {
        getObjectCalls += 1
        return requireNotNull(payloadsByKey[key]) { "Missing remote payload for $key" }
    }

    override suspend fun putObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
    ): S3PutObjectResult {
        putObjectCalls += 1
        putKeys += key
        objectsByKey[key] =
            S3RemoteObject(
                key = key,
                eTag = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) },
                lastModified = null,
                size = bytes.size.toLong(),
                metadata = metadata,
            )
        payloadsByKey[key] =
            S3RemoteObjectPayload(
                key = key,
                eTag = objectsByKey[key]?.eTag,
                lastModified = null,
                metadata = metadata,
                bytes = bytes,
            )
        return S3PutObjectResult(eTag = objectsByKey[key]?.eTag)
    }

    override suspend fun deleteObject(key: String) {
        deleteObjectCalls += 1
        objectsByKey.remove(key)
        payloadsByKey.remove(key)
    }

    override fun close() = Unit
}

private data class InitialRemoteFixture(
    val objectSummary: S3RemoteObject,
    val payload: S3RemoteObjectPayload,
)
