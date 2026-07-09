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
import com.lomo.data.local.entity.S3SyncMetadataEntity
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.s3.LomoS3Client
import com.lomo.data.s3.LomoS3ClientFactory
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.s3.S3PutObjectResult
import com.lomo.data.s3.S3RemoteObject
import com.lomo.data.s3.S3SmallObjectPayload
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3SyncErrorCode
import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncFailureException
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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull

/*
 * Behavior Contract:
 * - Unit under test: S3SyncActionApplier
 * - Behavior focus: (1) large media transfers under legacy direct-directory mode should use file-backed transfer paths instead of materializing the whole payload through LocalMediaSyncStore byte APIs; (2) every object upload — including non-memo media — should record a content md5 in the S3 object metadata and report the same fingerprint on downloads so classifiers can skip full GETs when ETags are multipart; (3) when the verification gate has already observed that a remote object is missing (observedMissingRemotePaths), upload / delete-remote / delete-local actions must trust that observation and not re-issue HEAD requests for the same key; (4) conditional-write-capable endpoints should receive If-None-Match / If-Match guards, while generic endpoints keep the HEAD-before-write compatibility path.
 * - Observable outcomes: Applied or Conflict results, uploaded bytes/metadata/conditional headers captured at the S3 client boundary, downloaded file contents, absence of LocalMediaSyncStore readBytes/writeBytes calls for large payloads, syncedContentFingerprint presence for media downloads, and HEAD call count on the S3 client for gate-observed-missing paths.
 * - TDD proof: Fails before the fix because (1) large upload/download actions still route through LocalMediaSyncStore.readBytes/writeBytes; (2) media uploads/downloads leave md5 metadata/fingerprint empty since memoContentFingerprint only returns md5 for memo paths; (3) applier verify helpers ignore observedMissingRemotePaths, so HEAD is re-issued even when the gate already saw the remote as missing; and (4) conditional-write headers / precondition failures are not locked by behavior tests.
 * - TDD proof: RED Report 10 command: ./kotlin test --include-classes='com.lomo.data.repository.S3SyncActionApplierTest'
 * - TDD proof: RED Report 10 symptom: upload with persisted old-prefix remotePath returned Applied and called putObjectFile("old-prefix/lomo/images/stale.png") instead of failing before the S3 client boundary.
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

        test("upload sends If-None-Match for create on conditional endpoint") { `upload sends If-None-Match for create on conditional endpoint`() }

        test("upload sends If-Match for overwrite on conditional endpoint") { `upload sends If-Match for overwrite on conditional endpoint`() }

        test("upload returns conflict when conditional write fails") { `upload returns conflict when conditional write fails`() }

        test("upload omits conditional headers for generic endpoint compatibility path") {
            `upload omits conditional headers for generic endpoint compatibility path`()
        }

        test("upload rejects stale persisted metadata key outside configured prefix before S3 client call") {
            `upload rejects stale persisted metadata key outside configured prefix before S3 client call`()
        }
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
        applier =
            S3SyncActionApplier(
                runtime = runtime,
                encodingSupport = encodingSupport,
                objectKeyPolicy = S3RemoteObjectKeyPolicy(encodingSupport),
                fileBridge = fileBridge,
                transferWorkspace = S3SyncTransferWorkspace.systemTemp(),
            )
    }

    private fun `large direct media upload bypasses local media byte reads`() =
        runTest {
            val path = "lomo/images/poster.png"
            val bytes = largePayload(seed = 7)
            val localFile = File(imageRoot, "poster.png").apply { writeBytes(bytes) }
            val client =
                RecordingS3Client(
                    metadataByKey =
                        mapOf(
                            path to
                                S3RemoteObject(
                                    key = path,
                                    eTag = "etag-old",
                                    lastModified = 10L,
                                    size = localFile.length(),
                                    metadata = emptyMap(),
                                ),
                        ),
                )
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
                                S3SmallObjectPayload(
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
            val client =
                RecordingS3Client(
                    metadataByKey =
                        mapOf(
                            path to
                                S3RemoteObject(
                                    key = path,
                                    eTag = "etag-old",
                                    lastModified = 10L,
                                    size = localFile.length(),
                                    metadata = emptyMap(),
                                ),
                        ),
                )
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
                                S3SmallObjectPayload(
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
                                    verificationLevel = com.lomo.data.repository.S3RemoteVerificationLevel.INDEX_CACHED_REMOTE,
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
                                    verificationLevel = com.lomo.data.repository.S3RemoteVerificationLevel.INDEX_CACHED_REMOTE,
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

    private fun `upload sends If-None-Match for create on conditional endpoint`() =
        runTest {
            val path = "lomo/images/new.png"
            val localFile = File(imageRoot, "new.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val client =
                RecordingS3Client(
                    metadataByKey =
                        mapOf(
                            path to
                                S3RemoteObject(
                                    key = path,
                                    eTag = "etag-old",
                                    lastModified = 10L,
                                    metadata = emptyMap(),
                                ),
                        ),
                )
            every { localMediaSyncStore.contentTypeForPath(path, layout) } returns "image/png"

            val result =
                applier.applyAction(
                    action = S3SyncAction(path, S3SyncDirection.UPLOAD, S3SyncReason.LOCAL_ONLY),
                    client = client,
                    layout = layout,
                    config = config.copy(endpointProfile = S3EndpointProfile.AWS_S3),
                    localFiles = mapOf(path to LocalS3File(path, localFile.lastModified(), localFile.length())),
                    remoteFiles = emptyMap(),
                    metadataByPath = emptyMap(),
                    verifiedMissingRemotePaths = emptySet(),
                    fileBridgeScope = fileBridge.modeAware(mode),
                    mode = mode,
                )

            (result is S3ActionExecutionState.Applied).shouldBeTrue()
            client.uploadedIfMatch[path] shouldBe null
            client.uploadedIfNoneMatch[path] shouldBe "*"
        }

    private fun `upload sends If-Match for overwrite on conditional endpoint`() =
        runTest {
            val path = "lomo/images/existing.png"
            val localFile = File(imageRoot, "existing.png").apply { writeBytes(byteArrayOf(4, 5, 6)) }
            val client = RecordingS3Client()
            every { localMediaSyncStore.contentTypeForPath(path, layout) } returns "image/png"

            val result =
                applier.applyAction(
                    action = S3SyncAction(path, S3SyncDirection.UPLOAD, S3SyncReason.LOCAL_NEWER),
                    client = client,
                    layout = layout,
                    config = config.copy(endpointProfile = S3EndpointProfile.AWS_S3),
                    localFiles = mapOf(path to LocalS3File(path, localFile.lastModified(), localFile.length())),
                    remoteFiles =
                        mapOf(
                            path to
                                RemoteS3File(
                                    path = path,
                                    etag = "etag-old",
                                    lastModified = 10L,
                                    remotePath = path,
                                    size = localFile.length(),
                                    verificationLevel = S3RemoteVerificationLevel.INDEX_CACHED_REMOTE,
                                ),
                        ),
                    metadataByPath = emptyMap(),
                    verifiedMissingRemotePaths = emptySet(),
                    fileBridgeScope = fileBridge.modeAware(mode),
                    mode = mode,
                )

            (result is S3ActionExecutionState.Applied).shouldBeTrue()
            client.uploadedIfMatch[path] shouldBe "etag-old"
            client.uploadedIfNoneMatch[path] shouldBe null
        }

    private fun `upload returns conflict when conditional write fails`() =
        runTest {
            val path = "lomo/images/existing.png"
            val localFile = File(imageRoot, "existing.png").apply { writeBytes(byteArrayOf(7, 8, 9)) }
            val client = RecordingS3Client().apply { failWithConditionalWriteFailed = true }
            every { localMediaSyncStore.contentTypeForPath(path, layout) } returns "image/png"

            val result =
                applier.applyAction(
                    action = S3SyncAction(path, S3SyncDirection.UPLOAD, S3SyncReason.LOCAL_NEWER),
                    client = client,
                    layout = layout,
                    config = config.copy(endpointProfile = S3EndpointProfile.AWS_S3),
                    localFiles = mapOf(path to LocalS3File(path, localFile.lastModified(), localFile.length())),
                    remoteFiles =
                        mapOf(
                            path to
                                RemoteS3File(
                                    path = path,
                                    etag = "etag-old",
                                    lastModified = 10L,
                                    remotePath = path,
                                    size = localFile.length(),
                                    verificationLevel = S3RemoteVerificationLevel.INDEX_CACHED_REMOTE,
                                ),
                        ),
                    metadataByPath = emptyMap(),
                    verifiedMissingRemotePaths = emptySet(),
                    fileBridgeScope = fileBridge.modeAware(mode),
                    mode = mode,
                )

            result shouldBe S3ActionExecutionState.Conflict(path)
        }

    private fun `upload omits conditional headers for generic endpoint compatibility path`() =
        runTest {
            val path = "lomo/images/existing.png"
            val localFile = File(imageRoot, "existing.png").apply { writeBytes(byteArrayOf(10, 11, 12)) }
            val client =
                RecordingS3Client(
                    metadataByKey =
                        mapOf(
                            path to
                                S3RemoteObject(
                                    key = path,
                                    eTag = "etag-old",
                                    lastModified = 10L,
                                    size = localFile.length(),
                                    metadata = emptyMap(),
                                ),
                        ),
                )
            every { localMediaSyncStore.contentTypeForPath(path, layout) } returns "image/png"

            val result =
                applier.applyAction(
                    action = S3SyncAction(path, S3SyncDirection.UPLOAD, S3SyncReason.LOCAL_NEWER),
                    client = client,
                    layout = layout,
                    config = config.copy(endpointProfile = S3EndpointProfile.GENERIC_S3),
                    localFiles = mapOf(path to LocalS3File(path, localFile.lastModified(), localFile.length())),
                    remoteFiles =
                        mapOf(
                            path to
                                RemoteS3File(
                                    path = path,
                                    etag = "etag-old",
                                    lastModified = 10L,
                                    remotePath = path,
                                    size = localFile.length(),
                                    verificationLevel = S3RemoteVerificationLevel.INDEX_CACHED_REMOTE,
                                ),
                        ),
                    metadataByPath = emptyMap(),
                    verifiedMissingRemotePaths = emptySet(),
                    fileBridgeScope = fileBridge.modeAware(mode),
                    mode = mode,
                )

            (result is S3ActionExecutionState.Applied).shouldBeTrue()
            client.headCalls[path] shouldBe 1
            client.uploadedIfMatch[path] shouldBe null
            client.uploadedIfNoneMatch[path] shouldBe null
        }

    private fun `upload rejects stale persisted metadata key outside configured prefix before S3 client call`() =
        runTest {
            val path = "lomo/images/stale.png"
            val staleRemotePath = "old-prefix/lomo/images/stale.png"
            val localFile = File(imageRoot, "stale.png").apply { writeBytes(byteArrayOf(1, 3, 5, 7)) }
            val client = RecordingS3Client()
            every { localMediaSyncStore.contentTypeForPath(path, layout) } returns "image/png"

            val error =
                shouldThrow<S3SyncFailureException> {
                applier.applyAction(
                    action = S3SyncAction(path, S3SyncDirection.UPLOAD, S3SyncReason.LOCAL_NEWER),
                    client = client,
                    layout = layout,
                    config = config.copy(prefix = "current-prefix"),
                    localFiles = mapOf(path to LocalS3File(path, localFile.lastModified(), localFile.length())),
                    remoteFiles = emptyMap(),
                    metadataByPath =
                        mapOf(
                            path to
                                S3SyncMetadataEntity(
                                    relativePath = path,
                                    remotePath = staleRemotePath,
                                    etag = "etag-old",
                                    remoteLastModified = 10L,
                                    remoteSize = localFile.length(),
                                    localLastModified = 10L,
                                    lastSyncedAt = 10L,
                                    lastResolvedDirection = "NONE",
                                    lastResolvedReason = "UNCHANGED",
                                ),
                        ),
                    verifiedMissingRemotePaths = emptySet(),
                    fileBridgeScope = fileBridge.modeAware(mode),
                    mode = mode,
                )
            }

            error.code shouldBe S3SyncErrorCode.REMOTE_LAYOUT_VIOLATION
            client.headCalls shouldBe emptyMap()
            client.uploadedBytes shouldBe emptyMap()
        }

    private fun largePayload(seed: Int): ByteArray =
        ByteArray(S3_LARGE_TRANSFER_BYTES.toInt() + 1) { index ->
            ((index + seed) % 251).toByte()
        }
}

private class RecordingS3Client(
    private val payloads: Map<String, S3SmallObjectPayload> = emptyMap(),
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

    override suspend fun getSmallObject(key: String): S3SmallObjectPayload = requireNotNull(payloads[key])

    override suspend fun getObjectMetadata(key: String): S3RemoteObject? {
        headCalls[key] = (headCalls[key] ?: 0) + 1
        return metadataByKey[key]
    }

    val uploadedIfMatch = mutableMapOf<String, String?>()
    val uploadedIfNoneMatch = mutableMapOf<String, String?>()
    var failWithConditionalWriteFailed = false

    override suspend fun putSmallObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
        ifMatch: String?,
        ifNoneMatch: String?,
    ): S3PutObjectResult {
        if (failWithConditionalWriteFailed) {
            return S3PutObjectResult(eTag = null, conditionalWriteFailed = true)
        }
        uploadedBytes[key] = bytes
        uploadedMetadata[key] = metadata
        uploadedIfMatch[key] = ifMatch
        uploadedIfNoneMatch[key] = ifNoneMatch
        return S3PutObjectResult(eTag = "etag-uploaded")
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

    override suspend fun deleteObject(key: String) {
        deletedKeys += key
    }

    override fun close() = Unit
}
