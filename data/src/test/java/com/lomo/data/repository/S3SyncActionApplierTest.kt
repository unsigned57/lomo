package com.lomo.data.repository

import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.s3.LomoS3Client
import com.lomo.data.s3.LomoS3ClientFactory
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.s3.S3PutObjectResult
import com.lomo.data.s3.S3RemoteObject
import com.lomo.data.s3.S3RemoteObjectPayload
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncReason
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/*
 * Test Contract:
 * - Unit under test: S3SyncActionApplier
 * - Behavior focus: large media transfers under legacy direct-directory mode should use file-backed transfer paths instead of materializing the whole payload through LocalMediaSyncStore byte APIs.
 * - Observable outcomes: successful Applied results, uploaded/downloaded file contents, and absence of LocalMediaSyncStore readBytes/writeBytes calls for large payloads.
 * - Red phase: Fails before the fix because large upload/download actions still route through LocalMediaSyncStore.readBytes/writeBytes, which proves the transfer path is byte-array based.
 * - Excludes: AWS SDK transport internals, planner action selection, metadata persistence, and reconcile behavior.
 */
class S3SyncActionApplierTest {
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
    private lateinit var metadataDao: S3SyncMetadataDao

    @MockK(relaxed = true)
    private lateinit var memoSynchronizer: MemoSynchronizer

    private lateinit var runtime: S3SyncRepositoryContext
    private lateinit var encodingSupport: S3SyncEncodingSupport
    private lateinit var fileBridge: S3SyncFileBridge
    private lateinit var applier: S3SyncActionApplier

    private lateinit var imageRoot: File
    private lateinit var voiceRoot: File
    private lateinit var layout: SyncDirectoryLayout
    private lateinit var mode: S3LocalSyncMode.Legacy
    private lateinit var config: S3ResolvedConfig

    @Before
    fun setUp() {
        MockKAnnotations.init(this)

        imageRoot = tempFolder.newFolder("images")
        voiceRoot = tempFolder.newFolder("voice")
        layout =
            SyncDirectoryLayout(
                memoFolder = "memo",
                imageFolder = "images",
                voiceFolder = "voice",
                allSameDirectory = false,
            )
        mode =
            S3LocalSyncMode.Legacy(
                directImageRoot = imageRoot,
                directVoiceRoot = voiceRoot,
            )
        config =
            S3ResolvedConfig(
                endpointUrl = "https://s3.example.com",
                region = "us-east-1",
                bucket = "bucket",
                prefix = "",
                accessKeyId = "access",
                secretAccessKey = "secret",
                sessionToken = null,
                pathStyle = S3PathStyle.AUTO,
                encryptionMode = S3EncryptionMode.NONE,
                encryptionPassword = null,
            )

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
                stateHolder = S3SyncStateHolder(),
            )
        encodingSupport = S3SyncEncodingSupport()
        fileBridge = S3SyncFileBridge(runtime, encodingSupport)
        applier = S3SyncActionApplier(runtime, encodingSupport, fileBridge)
    }

    @Test
    fun `large direct media upload bypasses local media byte reads`() =
        runTest {
            val path = "lomo/images/poster.png"
            val bytes = largePayload(seed = 7)
            val localFile = File(imageRoot, "poster.png").apply { writeBytes(bytes) }
            val client = RecordingS3Client()
            val action =
                S3SyncAction(
                    path = path,
                    direction = S3SyncDirection.UPLOAD,
                    reason = S3SyncReason.LOCAL_ONLY,
                )
            every { localMediaSyncStore.contentTypeForPath(path, layout) } returns "image/png"
            coEvery { localMediaSyncStore.readBytes(path, layout) } throws
                AssertionError("Large direct-media upload must not read through LocalMediaSyncStore.readBytes")

            val result =
                applier.applyAction(
                    action = action,
                    client = client,
                    layout = layout,
                    config = config,
                    localFiles =
                        mapOf(
                            path to
                                LocalS3File(
                                    path = path,
                                    lastModified = localFile.lastModified(),
                                    size = localFile.length(),
                                ),
                        ),
                    remoteFiles = emptyMap(),
                    metadataByPath = emptyMap(),
                    verifiedMissingRemotePaths = emptySet(),
                    fileBridgeScope = fileBridge.modeAware(mode),
                    mode = mode,
                )

            assertTrue(result is S3ActionExecutionState.Applied)
            assertArrayEquals(bytes, requireNotNull(client.uploadedBytes[path]))
            coVerify(exactly = 0) { localMediaSyncStore.readBytes(path, layout) }
        }

    @Test
    fun `large direct media download bypasses local media byte writes`() =
        runTest {
            val path = "lomo/images/banner.png"
            val bytes = largePayload(seed = 19)
            val client =
                RecordingS3Client(
                    payloads =
                        mapOf(
                            path to
                                S3RemoteObjectPayload(
                                    key = path,
                                    eTag = "etag-banner",
                                    lastModified = 20L,
                                    metadata = emptyMap(),
                                    bytes = bytes,
                                ),
                        ),
                )
            val action =
                S3SyncAction(
                    path = path,
                    direction = S3SyncDirection.DOWNLOAD,
                    reason = S3SyncReason.REMOTE_ONLY,
                )
            coEvery { localMediaSyncStore.writeBytes(path, any(), layout) } throws
                AssertionError("Large direct-media download must not write through LocalMediaSyncStore.writeBytes")

            val result =
                applier.applyAction(
                    action = action,
                    client = client,
                    layout = layout,
                    config = config,
                    localFiles = emptyMap(),
                    remoteFiles =
                        mapOf(
                            path to
                                RemoteS3File(
                                    path = path,
                                    etag = "etag-banner",
                                    lastModified = 20L,
                                    size = bytes.size.toLong(),
                                    remotePath = path,
                                ),
                        ),
                    metadataByPath = emptyMap(),
                    verifiedMissingRemotePaths = emptySet(),
                    fileBridgeScope = fileBridge.modeAware(mode),
                    mode = mode,
                )

            assertTrue(result is S3ActionExecutionState.Applied)
            assertEquals(bytes.size.toLong(), File(imageRoot, "banner.png").length())
            assertArrayEquals(bytes, File(imageRoot, "banner.png").readBytes())
            coVerify(exactly = 0) { localMediaSyncStore.writeBytes(path, any(), layout) }
        }

    private fun largePayload(seed: Int): ByteArray =
        ByteArray(S3_LARGE_TRANSFER_BYTES.toInt() + 1) { index ->
            ((index + seed) % 251).toByte()
        }
}

private class RecordingS3Client(
    private val payloads: Map<String, S3RemoteObjectPayload> = emptyMap(),
) : LomoS3Client {
    val uploadedBytes = linkedMapOf<String, ByteArray>()

    override suspend fun verifyAccess(prefix: String) = Unit

    override suspend fun list(
        prefix: String,
        maxKeys: Int?,
    ): List<S3RemoteObject> = emptyList()

    override suspend fun getObject(key: String): S3RemoteObjectPayload = requireNotNull(payloads[key])

    override suspend fun putObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
    ): S3PutObjectResult {
        uploadedBytes[key] = bytes
        return S3PutObjectResult(eTag = "etag-uploaded")
    }

    override suspend fun deleteObject(key: String) = Unit

    override fun close() = Unit
}
