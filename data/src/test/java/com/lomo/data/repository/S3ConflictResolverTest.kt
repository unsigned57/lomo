package com.lomo.data.repository

import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.s3.LomoS3Client
import com.lomo.data.s3.LomoS3ClientFactory
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.s3.S3OpenSslCompatCodec
import com.lomo.data.s3.S3PutObjectResult
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3SyncErrorCode
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.S3SyncState
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets

/*
 * Test Contract:
 * - Unit under test: S3ConflictResolver
 * - Behavior focus: conflict choice routing, encrypted remote key reuse, binary remote downloads, and error mapping.
 * - Observable outcomes: S3SyncResult type, state transitions, local/remote side-effect targets, and uploaded/downloaded payload contents.
 * - Red phase: Fails before the fix because S3 conflict resolution still returns a placeholder error and has no real resolver for KEEP_LOCAL or KEEP_REMOTE.
 * - Excludes: AWS SDK transport details, planner internals, metadata persistence internals, and UI rendering.
 */
class S3ConflictResolverTest {
    @MockK(relaxed = true)
    private lateinit var dataStore: LomoDataStore

    @MockK(relaxed = true)
    private lateinit var credentialStore: S3CredentialStore

    @MockK(relaxed = true)
    private lateinit var clientFactory: LomoS3ClientFactory

    @MockK(relaxed = true)
    private lateinit var client: LomoS3Client

    @MockK(relaxed = true)
    private lateinit var markdownStorageDataSource: MarkdownStorageDataSource

    @MockK(relaxed = true)
    private lateinit var localMediaSyncStore: LocalMediaSyncStore

    @MockK(relaxed = true)
    private lateinit var metadataDao: S3SyncMetadataDao

    @MockK(relaxed = true)
    private lateinit var memoSynchronizer: MemoSynchronizer

    private lateinit var stateHolder: S3SyncStateHolder
    private lateinit var runtime: S3SyncRepositoryContext
    private lateinit var support: S3SyncRepositorySupport
    private lateinit var encodingSupport: S3SyncEncodingSupport
    private lateinit var fileBridge: S3SyncFileBridge
    private lateinit var resolver: S3ConflictResolver

    @Before
    fun setUp() {
        MockKAnnotations.init(this)

        every { dataStore.s3SyncEnabled } returns flowOf(true)
        every { dataStore.s3EndpointUrl } returns flowOf("https://s3.example.com")
        every { dataStore.s3Region } returns flowOf("us-east-1")
        every { dataStore.s3Bucket } returns flowOf("lomo-bucket")
        every { dataStore.s3Prefix } returns flowOf("prefix")
        every { dataStore.s3PathStyle } returns flowOf("path_style")
        every { dataStore.s3EncryptionMode } returns flowOf("openssl")
        every { dataStore.s3LocalSyncDirectory } returns flowOf(null)
        every { dataStore.rootDirectory } returns flowOf("/memo")
        every { dataStore.rootUri } returns flowOf(null)
        every { dataStore.imageDirectory } returns flowOf("/images")
        every { dataStore.imageUri } returns flowOf(null)
        every { dataStore.voiceDirectory } returns flowOf("/voice")
        every { dataStore.voiceUri } returns flowOf(null)
        every { credentialStore.getAccessKeyId() } returns "access"
        every { credentialStore.getSecretAccessKey() } returns "secret-key"
        every { credentialStore.getSessionToken() } returns null
        every { credentialStore.getEncryptionPassword() } returns "secret"
        every { clientFactory.create(any()) } returns client
        coEvery { memoSynchronizer.refresh() } returns Unit
        coEvery { metadataDao.getAll() } returns emptyList()

        stateHolder = S3SyncStateHolder()
        runtime =
            S3SyncRepositoryContext(
                dataStore = dataStore,
                credentialStore = credentialStore,
                clientFactory = clientFactory,
                markdownStorageDataSource = markdownStorageDataSource,
                localMediaSyncStore = localMediaSyncStore,
                metadataDao = metadataDao,
                memoSynchronizer = memoSynchronizer,
                planner = S3SyncPlanner(),
                stateHolder = stateHolder,
            )
        support = S3SyncRepositorySupport(runtime)
        encodingSupport = S3SyncEncodingSupport()
        fileBridge = S3SyncFileBridge(runtime, encodingSupport)
        resolver = S3ConflictResolver(runtime, support, encodingSupport, fileBridge)
    }

    @Test
    fun `resolveConflicts KEEP_LOCAL uploads local memo to existing encrypted remote key`() =
        runTest {
            val path = "lomo/memo/2026_03_24.md"
            val remotePath = encryptedRemotePath(path)
            val uploadedKey = slot<String>()
            val uploadedBytes = slot<ByteArray>()

            coEvery { client.list(prefix = "prefix/", maxKeys = null) } returns
                listOf(remoteObject(remotePath))
            coEvery { markdownStorageDataSource.readFileIn(com.lomo.data.source.MemoDirectoryType.MAIN, "2026_03_24.md") } returns "LOCAL"
            coEvery {
                client.putObject(
                    key = capture(uploadedKey),
                    bytes = capture(uploadedBytes),
                    contentType = any(),
                    metadata = any(),
                )
            } returns S3PutObjectResult(eTag = "etag-upload")

            val result =
                resolver.resolveConflicts(
                    resolution =
                        SyncConflictResolution(
                            perFileChoices = mapOf(path to SyncConflictResolutionChoice.KEEP_LOCAL),
                        ),
                    conflictSet = conflictSet(conflictFile(path = path, local = "LOCAL", remote = "REMOTE")),
                )

            assertEquals(S3SyncResult.Success("Conflicts resolved"), result)
            assertEquals(remotePath, uploadedKey.captured)
            assertEquals(
                "LOCAL",
                String(
                    S3OpenSslCompatCodec().decryptBytes(uploadedBytes.captured, "secret"),
                    StandardCharsets.UTF_8,
                ),
            )
            coVerify(exactly = 1) { memoSynchronizer.refresh() }
            assertTrue(stateHolder.state.value is S3SyncState.Success)
        }

    @Test
    fun `resolveConflicts KEEP_REMOTE memo downloads remote content into MAIN directory`() =
        runTest {
            val path = "lomo/memo/2026_03_24.md"
            val remotePath = encryptedRemotePath(path)
            val payload = S3OpenSslCompatCodec().encryptBytes("REMOTE".toByteArray(StandardCharsets.UTF_8), "secret")
            coEvery { client.list(prefix = "prefix/", maxKeys = null) } returns
                listOf(remoteObject(remotePath))
            coEvery { client.getObject(remotePath) } returns remotePayload(remotePath, payload)
            coEvery {
                markdownStorageDataSource.saveFileIn(
                    directory = com.lomo.data.source.MemoDirectoryType.MAIN,
                    filename = "2026_03_24.md",
                    content = "REMOTE",
                    append = false,
                    uri = null,
                )
            } returns null

            val result =
                resolver.resolveConflicts(
                    resolution =
                        SyncConflictResolution(
                            perFileChoices = mapOf(path to SyncConflictResolutionChoice.KEEP_REMOTE),
                        ),
                    conflictSet = conflictSet(conflictFile(path = path, local = "LOCAL", remote = "REMOTE")),
                )

            assertEquals(S3SyncResult.Success("Conflicts resolved"), result)
            coVerify(exactly = 1) { client.getObject(remotePath) }
            coVerify(exactly = 1) {
                markdownStorageDataSource.saveFileIn(
                    directory = com.lomo.data.source.MemoDirectoryType.MAIN,
                    filename = "2026_03_24.md",
                    content = "REMOTE",
                    append = false,
                    uri = null,
                )
            }
            coVerify(exactly = 0) { localMediaSyncStore.writeBytes(any(), any(), any()) }
        }

    @Test
    fun `resolveConflicts KEEP_REMOTE media writes remote bytes into local media store`() =
        runTest {
            every { dataStore.s3EncryptionMode } returns flowOf(S3EncryptionMode.NONE.name.lowercase())
            val path = "lomo/images/a.png"
            val remotePath = "prefix/$path"
            val bytes = byteArrayOf(0x00, 0x11, 0x22, 0x33)
            coEvery { client.list(prefix = "prefix/", maxKeys = null) } returns
                listOf(remoteObject(remotePath))
            coEvery { client.getObject(remotePath) } returns remotePayload(remotePath, bytes)

            val result =
                resolver.resolveConflicts(
                    resolution =
                        SyncConflictResolution(
                            perFileChoices = mapOf(path to SyncConflictResolutionChoice.KEEP_REMOTE),
                        ),
                    conflictSet = conflictSet(conflictFile(path = path, local = null, remote = null, isBinary = true)),
                )

            assertEquals(S3SyncResult.Success("Conflicts resolved"), result)
            coVerify(exactly = 1) {
                localMediaSyncStore.writeBytes(
                    relativePath = path,
                    bytes = bytes,
                    layout = any<SyncDirectoryLayout>(),
                )
            }
            coVerify(exactly = 0) { markdownStorageDataSource.saveFileIn(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `resolveConflicts returns NotConfigured when config is missing`() =
        runTest {
            every { dataStore.s3SyncEnabled } returns flowOf(false)

            val result =
                resolver.resolveConflicts(
                    resolution = SyncConflictResolution(emptyMap()),
                    conflictSet = conflictSet(conflictFile(path = "lomo/memo/2026_03_24.md", local = "L", remote = "R")),
                )

            assertEquals(S3SyncResult.NotConfigured, result)
            assertEquals(S3SyncState.NotConfigured, stateHolder.state.value)
            verify(exactly = 0) { clientFactory.create(any()) }
        }

    @Test
    fun `resolveConflicts maps resolver exception to S3SyncResult Error`() =
        runTest {
            every { clientFactory.create(any()) } throws IllegalStateException("client init failed")

            val result =
                resolver.resolveConflicts(
                    resolution = SyncConflictResolution(emptyMap()),
                    conflictSet = conflictSet(conflictFile(path = "lomo/memo/2026_03_24.md", local = "L", remote = "R")),
                )

            assertTrue(result is S3SyncResult.Error)
            result as S3SyncResult.Error
            assertEquals(S3SyncErrorCode.UNKNOWN, result.code)
            assertTrue(result.message.contains("client init failed"))
            assertTrue(result.exception is IllegalStateException)
            assertTrue(stateHolder.state.value is S3SyncState.Error)
        }

    private fun encryptedRemotePath(relativePath: String): String =
        "prefix/" + S3OpenSslCompatCodec { byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8) }.encryptKey(relativePath, "secret")

    private fun remoteObject(remotePath: String) =
        com.lomo.data.s3.S3RemoteObject(
            key = remotePath,
            eTag = "etag",
            lastModified = 1L,
            metadata = emptyMap(),
        )

    private fun remotePayload(
        remotePath: String,
        bytes: ByteArray,
    ) = com.lomo.data.s3.S3RemoteObjectPayload(
        key = remotePath,
        eTag = "etag",
        lastModified = 1L,
        metadata = emptyMap(),
        bytes = bytes,
    )

    private fun conflictSet(file: SyncConflictFile): SyncConflictSet =
        SyncConflictSet(
            source = SyncBackendType.S3,
            files = listOf(file),
            timestamp = 1L,
        )

    private fun conflictFile(
        path: String,
        local: String?,
        remote: String?,
        isBinary: Boolean = false,
    ): SyncConflictFile =
        SyncConflictFile(
            relativePath = path,
            localContent = local,
            remoteContent = remote,
            isBinary = isBinary,
        )
}
