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
import java.io.File
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.KotestTemporaryFolder
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull

/*
 * Behavior Contract:
 * - Unit under test: S3SyncActionApplier
 * - Behavior focus: (1) large media transfers under legacy direct-directory mode should use file-backed transfer paths instead of materializing the whole payload through LocalMediaSyncStore byte APIs; (2) every object upload — including non-memo media — should record a content md5 in the S3 object metadata and report the same fingerprint on downloads so classifiers can skip full GETs when ETags are multipart; (3) when the verification gate has already observed that a remote object is missing (observedMissingRemotePaths), upload / delete-remote / delete-local actions must trust that observation and not re-issue HEAD requests for the same key.
 * - Observable outcomes: Applied results, uploaded bytes/metadata captured at the S3 client boundary, downloaded file contents, absence of LocalMediaSyncStore readBytes/writeBytes calls for large payloads, syncedContentFingerprint presence for media downloads, and HEAD call count on the S3 client for gate-observed-missing paths.
 * - TDD proof: Fails before the fix because (1) large upload/download actions still route through LocalMediaSyncStore.readBytes/writeBytes; (2) media uploads/downloads leave md5 metadata/fingerprint empty since memoContentFingerprint only returns md5 for memo paths; and (3) applier verify helpers ignore observedMissingRemotePaths, so HEAD is re-issued even when the gate already saw the remote as missing.
 * - Excludes: AWS SDK transport internals, planner action selection, metadata persistence, and reconcile behavior.
 */
class S3SyncActionApplierTest : DataFunSpec() {
    init {
        beforeTest {
            tempFolder = KotestTemporaryFolder()
            setUp()
        }

        afterTest {
            tempFolder.cleanup()
        }

        test("large direct media upload bypasses local media byte reads") { `large direct media upload bypasses local media byte reads`() }

        test("large direct media download bypasses local media byte writes") { `large direct media download bypasses local media byte writes`() }

        test("upload records md5 metadata for non-memo media") { `upload records md5 metadata for non-memo media`() }

        test("download records md5 fingerprint for non-memo media") { `download records md5 fingerprint for non-memo media`() }

        test("upload skips HEAD when path is in observedMissingRemotePaths") { `upload skips HEAD when path is in observedMissingRemotePaths`() }

        test("delete remote skips HEAD when path is in observedMissingRemotePaths") { `delete remote skips HEAD when path is in observedMissingRemotePaths`() }

        test("delete local skips HEAD when path is in observedMissingRemotePaths") { `delete local skips HEAD when path is in observedMissingRemotePaths`() }
    }


    private lateinit var tempFolder: KotestTemporaryFolder
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
                performanceTuner = DisabledSyncPerformanceTuner,
                transactionRunner = NoOpS3SyncTransactionRunner,
            )
        encodingSupport = S3SyncEncodingSupport()
        fileBridge = S3SyncFileBridge(runtime, encodingSupport)
        applier = S3SyncActionApplier(runtime, encodingSupport, fileBridge)
    }

    private fun `large direct media upload bypasses local media byte reads`() =
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

            (result is S3ActionExecutionState.Applied).shouldBeTrue()
            requireNotNull(client.uploadedBytes[path]) shouldBe bytes
            coVerify(exactly = 0) { localMediaSyncStore.readBytes(path, layout) }
        }

    private fun `large direct media download bypasses local media byte writes`() =
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

            (result is S3ActionExecutionState.Applied).shouldBeTrue()
            File(imageRoot, "banner.png").length() shouldBe bytes.size.toLong()
            File(imageRoot, "banner.png").readBytes() shouldBe bytes
            coVerify(exactly = 0) { localMediaSyncStore.writeBytes(path, any(), layout) }
        }

    private fun `upload records md5 metadata for non-memo media`() =
        runTest {
            val path = "lomo/images/pic.png"
            val bytes = byteArrayOf(1, 2, 3, 4, 5)
            val localFile = File(imageRoot, "pic.png").apply { writeBytes(bytes) }
            val client = RecordingS3Client()
            val action =
                S3SyncAction(
                    path = path,
                    direction = S3SyncDirection.UPLOAD,
                    reason = S3SyncReason.LOCAL_ONLY,
                )
            every { localMediaSyncStore.contentTypeForPath(path, layout) } returns "image/png"

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

            (result is S3ActionExecutionState.Applied).shouldBeTrue()
            val metadata = requireNotNull(client.uploadedMetadata[path])
            metadata["md5"] shouldBe bytes.md5Hex()
        }

    private fun `download records md5 fingerprint for non-memo media`() =
        runTest {
            val path = "lomo/images/banner.png"
            val bytes = byteArrayOf(9, 8, 7, 6, 5)
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

            (result is S3ActionExecutionState.Applied).shouldBeTrue()
            val applied = result as S3ActionExecutionState.Applied
            applied.syncedContentFingerprint.shouldNotBeNull()
            applied.syncedContentFingerprint shouldBe bytes.md5Hex()
        }

    private fun `upload skips HEAD when path is in observedMissingRemotePaths`() =
        runTest {
            val path = "lomo/images/new.png"
            val bytes = byteArrayOf(1, 2, 3)
            val localFile = File(imageRoot, "new.png").apply { writeBytes(bytes) }
            val client = RecordingS3Client()
            val action =
                S3SyncAction(
                    path = path,
                    direction = S3SyncDirection.UPLOAD,
                    reason = S3SyncReason.LOCAL_ONLY,
                )
            every { localMediaSyncStore.contentTypeForPath(path, layout) } returns "image/png"

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
                    remoteFiles =
                        mapOf(
                            path to
                                RemoteS3File(
                                    path = path,
                                    etag = "etag-stale",
                                    lastModified = 10L,
                                    remotePath = path,
                                    verificationLevel = com.lomo.domain.model.S3RemoteVerificationLevel.INDEX_CACHED_REMOTE,
                                ),
                        ),
                    metadataByPath = emptyMap(),
                    verifiedMissingRemotePaths = emptySet(),
                    fileBridgeScope = fileBridge.modeAware(mode),
                    mode = mode,
                    observedMissingRemotePaths = setOf(path),
                )

            (result is S3ActionExecutionState.Applied).shouldBeTrue()
            withClue("expected no HEAD call when path is known-missing from gate; saw ${client.headCalls}") { (client.headCalls[path] == null).shouldBeTrue() }
        }

    private fun `delete remote skips HEAD when path is in observedMissingRemotePaths`() =
        runTest {
            val path = "lomo/images/gone.png"
            val client = RecordingS3Client()
            val action =
                S3SyncAction(
                    path = path,
                    direction = S3SyncDirection.DELETE_REMOTE,
                    reason = S3SyncReason.LOCAL_ONLY,
                )

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
                                    etag = "etag-stale",
                                    lastModified = 10L,
                                    remotePath = path,
                                    verificationLevel = com.lomo.domain.model.S3RemoteVerificationLevel.INDEX_CACHED_REMOTE,
                                ),
                        ),
                    metadataByPath = emptyMap(),
                    verifiedMissingRemotePaths = emptySet(),
                    fileBridgeScope = fileBridge.modeAware(mode),
                    mode = mode,
                    observedMissingRemotePaths = setOf(path),
                )

            (result is S3ActionExecutionState.Applied).shouldBeTrue()
            withClue("expected no HEAD call when remote is known-missing; saw ${client.headCalls}") { (client.headCalls[path] == null).shouldBeTrue() }
            withClue("delete-remote should not issue a delete when remote is already missing; saw ${client.deletedKeys}") { (client.deletedKeys.isEmpty()).shouldBeTrue() }
        }

    private fun `delete local skips HEAD when path is in observedMissingRemotePaths`() =
        runTest {
            val path = "lomo/images/gone.png"
            val client = RecordingS3Client()
            val action =
                S3SyncAction(
                    path = path,
                    direction = S3SyncDirection.DELETE_LOCAL,
                    reason = S3SyncReason.REMOTE_ONLY,
                )

            val result =
                applier.applyAction(
                    action = action,
                    client = client,
                    layout = layout,
                    config = config,
                    localFiles = emptyMap(),
                    remoteFiles = emptyMap(),
                    metadataByPath = emptyMap(),
                    verifiedMissingRemotePaths = emptySet(),
                    fileBridgeScope = fileBridge.modeAware(mode),
                    mode = mode,
                    observedMissingRemotePaths = setOf(path),
                )

            (result is S3ActionExecutionState.Applied).shouldBeTrue()
            withClue("expected no HEAD call when delete-local knows remote is missing; saw ${client.headCalls}") { (client.headCalls[path] == null).shouldBeTrue() }
        }

    private fun largePayload(seed: Int): ByteArray =
        ByteArray(S3_LARGE_TRANSFER_BYTES.toInt() + 1) { index ->
            ((index + seed) % 251).toByte()
        }
}

private class RecordingS3Client(
    private val payloads: Map<String, S3RemoteObjectPayload> = emptyMap(),
    private val metadataByKey: Map<String, S3RemoteObject> = emptyMap(),
) : LomoS3Client {
    val uploadedBytes = linkedMapOf<String, ByteArray>()
    val uploadedMetadata = linkedMapOf<String, Map<String, String>>()
    val headCalls = linkedMapOf<String, Int>()
    val deletedKeys = mutableListOf<String>()

    override suspend fun verifyAccess(prefix: String) = Unit

    override suspend fun list(
        prefix: String,
        maxKeys: Int?,
    ): List<S3RemoteObject> = emptyList()

    override suspend fun getObject(key: String): S3RemoteObjectPayload = requireNotNull(payloads[key])

    override suspend fun getObjectMetadata(key: String): S3RemoteObject? {
        headCalls[key] = (headCalls[key] ?: 0) + 1
        return metadataByKey[key]
    }

    override suspend fun putObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
    ): S3PutObjectResult {
        uploadedBytes[key] = bytes
        uploadedMetadata[key] = metadata
        return S3PutObjectResult(eTag = "etag-uploaded")
    }

    override suspend fun deleteObject(key: String) {
        deletedKeys += key
    }

    override fun close() = Unit
}
