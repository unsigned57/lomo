package com.lomo.data.repository

import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.local.entity.S3SyncMetadataEntity
import com.lomo.data.s3.LomoS3Client
import com.lomo.data.s3.LomoS3ClientFactory
import com.lomo.data.s3.S3CredentialStore
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/*
 * Test Contract:
 * - Unit under test: S3SyncExecutor
 * - Behavior focus: sync execution should avoid redundant remote rescans after remote mutations and should scope memo refresh work to only the affected memo files.
 * - Observable outcomes: returned S3SyncResult outcomes, remote list invocation count, and memo refresh targets issued after sync.
 * - Red phase: Fails before the fix because remote-changing syncs re-list S3 during metadata persistence, attachment-only downloads trigger full memo refreshes, and memo downloads refresh the whole cache instead of the touched file.
 * - Excludes: AWS SDK transport internals, Room implementation details, and UI rendering.
 */
class S3SyncExecutorPerformancePolicyTest {
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

    private lateinit var executor: S3SyncExecutor

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
        every { clientFactory.create(any()) } returns client

        coEvery { markdownStorageDataSource.listMetadataIn(MemoDirectoryType.MAIN) } returns emptyList()
        coEvery { localMediaSyncStore.listFiles(any()) } returns emptyMap()
        coEvery { metadataDao.getAll() } returns emptyList()
        coEvery { metadataDao.replaceAll(any()) } returns Unit
        coEvery { memoSynchronizer.refresh(any()) } returns Unit

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
        val fileBridge = S3SyncFileBridge(runtime, encodingSupport)
        executor =
            S3SyncExecutor(
                runtime = runtime,
                support = S3SyncRepositorySupport(runtime),
                encodingSupport = encodingSupport,
                fileBridge = fileBridge,
                actionApplier = S3SyncActionApplier(runtime, encodingSupport, fileBridge),
            )
    }

    @Test
    fun `performSync does not re-list remote files after delete remote action`() =
        runTest {
            val managedPath = "lomo/memo/note.md"
            coEvery { client.list(prefix = "", maxKeys = null) } returns
                listOf(remoteObject(managedPath, eTag = "etag-1", lastModified = 10L))
            coEvery { metadataDao.getAll() } returns
                listOf(
                    S3SyncMetadataEntity(
                        relativePath = managedPath,
                        remotePath = managedPath,
                        etag = "etag-1",
                        remoteLastModified = 10L,
                        localLastModified = 10L,
                        lastSyncedAt = 10L,
                        lastResolvedDirection = "NONE",
                        lastResolvedReason = "UNCHANGED",
                    ),
                )
            coEvery { client.deleteObject(managedPath) } returns Unit

            val result = executor.performSync()

            val success = result as S3SyncResult.Success
            assertEquals("S3 sync completed", success.message)
            assertEquals(
                listOf(S3SyncDirection.DELETE_REMOTE to S3SyncReason.LOCAL_DELETED),
                success.outcomes.map { it.direction to it.reason },
            )
            coVerify(exactly = 1) { client.list(prefix = "", maxKeys = null) }
        }

    @Test
    fun `performSync skips memo refresh for attachment only download`() =
        runTest {
            val imagePath = "lomo/images/cover.png"
            coEvery { client.list(prefix = "", maxKeys = null) } returns
                listOf(remoteObject(imagePath, eTag = "etag-image", lastModified = 20L))
            coEvery { client.getObject(imagePath) } returns
                remotePayload(
                    key = imagePath,
                    eTag = "etag-image",
                    lastModified = 20L,
                    bytes = byteArrayOf(1, 2, 3),
                )
            coEvery { localMediaSyncStore.writeBytes(imagePath, any(), any()) } returns Unit

            val result = executor.performSync()

            val success = result as S3SyncResult.Success
            assertEquals("S3 sync completed", success.message)
            assertEquals(
                listOf(S3SyncDirection.DOWNLOAD to S3SyncReason.REMOTE_ONLY),
                success.outcomes.map { it.direction to it.reason },
            )
            coVerify(exactly = 0) { memoSynchronizer.refresh(any()) }
        }

    @Test
    fun `performSync refreshes only the downloaded memo target`() =
        runTest {
            val memoRemotePath = "lomo/memo/note.md"
            val memoBytes = "# note".toByteArray()
            coEvery { client.list(prefix = "", maxKeys = null) } returns
                listOf(remoteObject(memoRemotePath, eTag = "etag-note", lastModified = 30L))
            coEvery { client.getObject(memoRemotePath) } returns
                remotePayload(
                    key = memoRemotePath,
                    eTag = "etag-note",
                    lastModified = 30L,
                    bytes = memoBytes,
                )
            coEvery {
                markdownStorageDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = "note.md",
                    content = "# note",
                    append = false,
                    uri = null,
                )
            } returns null

            val result = executor.performSync()

            val success = result as S3SyncResult.Success
            assertEquals("S3 sync completed", success.message)
            assertEquals(
                listOf(S3SyncDirection.DOWNLOAD to S3SyncReason.REMOTE_ONLY),
                success.outcomes.map { it.direction to it.reason },
            )
            coVerify(exactly = 1) { memoSynchronizer.refresh("note.md") }
            coVerify(exactly = 0) { memoSynchronizer.refresh() }
        }

    @Test
    fun `performSync skips memo refresh for downloaded non memo markdown under explicit vault root`() =
        runTest {
            val vaultRoot = Files.createTempDirectory("s3-refresh-vault-root").toFile()
            val memoRoot = vaultRoot.resolve("journal").also { it.mkdirs() }
            val imageRoot = vaultRoot.resolve("asset").also { it.mkdirs() }
            val voiceRoot = vaultRoot.resolve("voice").also { it.mkdirs() }
            every { dataStore.s3LocalSyncDirectory } returns flowOf(vaultRoot.absolutePath)
            every { dataStore.rootDirectory } returns flowOf(memoRoot.absolutePath)
            every { dataStore.imageDirectory } returns flowOf(imageRoot.absolutePath)
            every { dataStore.voiceDirectory } returns flowOf(voiceRoot.absolutePath)
            val boardRemotePath = "pages.kanban/board.md"
            val boardBytes = "# board".toByteArray()
            coEvery { client.list(prefix = "", maxKeys = null) } returns
                listOf(remoteObject(boardRemotePath, eTag = "etag-board", lastModified = 40L))
            coEvery { client.getObject(boardRemotePath) } returns
                remotePayload(
                    key = boardRemotePath,
                    eTag = "etag-board",
                    lastModified = 40L,
                    bytes = boardBytes,
                )

            val result = executor.performSync()

            val success = result as S3SyncResult.Success
            assertEquals("S3 sync completed", success.message)
            assertEquals(
                listOf(S3SyncDirection.DOWNLOAD to S3SyncReason.REMOTE_ONLY),
                success.outcomes.map { it.direction to it.reason },
            )
            coVerify(exactly = 0) { memoSynchronizer.refresh(any()) }
        }

    private fun remoteObject(
        key: String,
        eTag: String,
        lastModified: Long,
    ) = com.lomo.data.s3.S3RemoteObject(
        key = key,
        eTag = eTag,
        lastModified = lastModified,
        metadata = emptyMap(),
    )

    private fun remotePayload(
        key: String,
        eTag: String,
        lastModified: Long,
        bytes: ByteArray,
    ) = com.lomo.data.s3.S3RemoteObjectPayload(
        key = key,
        eTag = eTag,
        lastModified = lastModified,
        metadata = emptyMap(),
        bytes = bytes,
    )
}
