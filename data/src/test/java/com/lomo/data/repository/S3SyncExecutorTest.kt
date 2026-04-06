package com.lomo.data.repository

import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.local.entity.S3SyncMetadataEntity
import com.lomo.data.s3.LomoS3Client
import com.lomo.data.s3.LomoS3ClientFactory
import com.lomo.data.s3.S3RemoteListPage
import com.lomo.data.s3.S3RemoteObject
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.source.MarkdownStorageDataSource
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/*
 * Test Contract:
 * - Unit under test: S3SyncExecutor
 * - Behavior focus: sync execution must apply delete/upload/download actions only to files inside the filtered S3 sync set, leaving ignored external objects untouched.
 * - Observable outcomes: returned S3SyncResult outcomes and remote delete targets issued to the S3 client.
 * - Red phase: Fails before the fix when plaintext S3 listings are not filtered to the content-only sync scope, allowing ignored external objects to influence execution-side deletes or mismatch handling.
 * - Excludes: AWS SDK transport behavior, Compose/UI rendering, and Room persistence implementation details.
 */
class S3SyncExecutorTest {
    @get:Rule val tempFolder = TemporaryFolder()

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

        val vaultRoot = tempFolder.newFolder("vault-root")
        File(vaultRoot, "memo").mkdirs()
        File(vaultRoot, "attachments/images").mkdirs()
        File(vaultRoot, "attachments/voice").mkdirs()

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
        every { dataStore.rootDirectory } returns flowOf(File(vaultRoot, "memo").absolutePath)
        every { dataStore.rootUri } returns flowOf(null)
        every { dataStore.imageDirectory } returns flowOf(File(vaultRoot, "attachments/images").absolutePath)
        every { dataStore.imageUri } returns flowOf(null)
        every { dataStore.voiceDirectory } returns flowOf(File(vaultRoot, "attachments/voice").absolutePath)
        every { dataStore.voiceUri } returns flowOf(null)
        every { credentialStore.getAccessKeyId() } returns "access"
        every { credentialStore.getSecretAccessKey() } returns "secret"
        every { credentialStore.getSessionToken() } returns null
        every { credentialStore.getEncryptionPassword() } returns null
        every { credentialStore.getEncryptionPassword2() } returns null
        every { clientFactory.create(any()) } returns client
        coEvery { memoSynchronizer.refresh() } returns Unit

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
        executor =
            S3SyncExecutor(
                runtime = runtime,
                support = S3SyncRepositorySupport(runtime),
                encodingSupport = S3SyncEncodingSupport(),
                fileBridge = S3SyncFileBridge(runtime, S3SyncEncodingSupport()),
                actionApplier = S3SyncActionApplier(runtime, S3SyncEncodingSupport(), S3SyncFileBridge(runtime, S3SyncEncodingSupport())),
            )
    }

    @Test
    fun `performSync deletes only syncable remote objects and ignores external plaintext objects`() =
        runTest {
            val managedPath = "Projects/note.md"
            val externalPath = ".obsidian/workspace.json"
            stubSinglePageRemoteScan(
                remoteObject(managedPath),
                remoteObject(externalPath),
            )
            coEvery { metadataDao.getAll() } returns
                listOf(
                    S3SyncMetadataEntity(
                        relativePath = managedPath,
                        remotePath = managedPath,
                        etag = "etag",
                        remoteLastModified = 10L,
                        localLastModified = 10L,
                        lastSyncedAt = 10L,
                        lastResolvedDirection = "NONE",
                        lastResolvedReason = "UNCHANGED",
                    ),
                )
            coEvery { client.deleteObject(managedPath) } returns Unit
            coEvery { metadataDao.replaceAll(any()) } returns Unit

            val result = executor.performSync()

            val success = result as S3SyncResult.Success
            assertEquals("S3 sync completed", success.message)
            assertEquals(
                listOf(S3SyncDirection.DELETE_REMOTE to S3SyncReason.LOCAL_DELETED),
                success.outcomes.map { it.direction to it.reason },
            )
            coVerify(exactly = 1) { client.deleteObject(managedPath) }
            coVerify(exactly = 0) { client.deleteObject(externalPath) }
        }

    private fun remoteObject(key: String) =
        S3RemoteObject(
            key = key,
            eTag = "etag",
            lastModified = 10L,
            metadata = emptyMap(),
        )

    private fun stubSinglePageRemoteScan(vararg objects: S3RemoteObject) {
        coEvery { client.listPage(prefix = any(), continuationToken = any(), maxKeys = any()) } answers {
            val prefix = firstArg<String>()
            val continuationToken = secondArg<String?>()
            S3RemoteListPage(
                objects =
                    if (continuationToken == null) {
                        objects.filter { remoteObject -> remoteObject.key.startsWith(prefix) }
                    } else {
                        emptyList()
                    },
                nextContinuationToken = null,
            )
        }
    }
}
