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
import com.lomo.data.s3.S3RemoteListPage
import com.lomo.data.s3.S3RemoteObject
import com.lomo.data.s3.S3SmallObjectPayload
import com.lomo.data.source.FileMetadata
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncReason
import com.lomo.domain.model.S3SyncResult
import com.lomo.data.repository.S3SyncWorkIntent
import com.lomo.domain.model.SyncBackendType
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue

/*
 * Behavior Contract:
 * - Unit under test: S3SyncExecutor
 * - Behavior focus: manifest-free S3 sync should reuse the local remote index for fast paths, reconcile with a full remote listing only when the cached index is stale, verify destructive local-delete candidates without scanning the whole bucket, use content fingerprints to resolve tracked memo drift when etags are unreliable, and rely on conditional writes instead of HEAD-before-PUT when a fresh index makes the cached remote version trustworthy.
 * - Observable outcomes: returned S3SyncResult, remote list/head/get invocation counts, uploaded/deleted remote keys, conditional write headers, metadata fingerprint persistence, conflict materialization, and local journal drain behavior.
 * - TDD proof: Fails before the fix because sync still probes the retired manifest protocol, cannot execute fast paths or targeted destructive verification without touching manifest-specific remote objects, treats multipart-etag memo updates as conflicts instead of resolving them via content fingerprints, heads fresh-index local-only upload candidates before writing, and reports conditional-write precondition failures as a successful sync instead of a materialized conflict.
 * - Excludes: AWS SDK transport internals, Room generated code, WorkManager scheduling, and UI rendering.
 */
class S3SyncIncrementalExecutorTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("performSync skips remote probing when cached index is fresh and journal is empty") { `performSync skips remote probing when cached index is fresh and journal is empty`() }

        test("performSync uploads local journal change from cached index without full remote scan") { `performSync uploads local journal change from cached index without full remote scan`() }

        test("performSync uploads cached remote change from fresh index without head when conditional writes are supported") {
            `performSync uploads cached remote change from fresh index without head when conditional writes are supported`()
        }

        test("performSync materializes conflict when conditional upload precondition fails") {
            `performSync materializes conflict when conditional upload precondition fails`()
        }

        test("performSync rolls back metadata, remote index, and journal when final protocol commit fails") { `performSync rolls back metadata, remote index, and journal when final protocol commit fails`() }

        test("performSync falls back to full remote reconciliation when cached index is stale") { `performSync falls back to full remote reconciliation when cached index is stale`() }

        test("performSync verifies cached remote deletion candidate without whole bucket scan") { `performSync verifies cached remote deletion candidate without whole bucket scan`() }

        test("performSync deletes remote journal target from remote index when metadata snapshot is missing") { `performSync deletes remote journal target from remote index when metadata snapshot is missing`() }

        test("performSync downloads remotely updated file before applying cached delete intent") { `performSync downloads remotely updated file before applying cached delete intent`() }

        test("performSync surfaces conflict when upload candidate changed remotely during fast path") { `performSync surfaces conflict when upload candidate changed remotely during fast path`() }

        test("performSync suppresses tracked memo conflict when local and remote content still match baseline") { `performSync suppresses tracked memo conflict when local and remote content still match baseline`() }

        test("performSync uploads tracked memo when remote matches baseline but local fingerprint changed") { `performSync uploads tracked memo when remote matches baseline but local fingerprint changed`() }

        test("performSync resolves tracked memo from head metadata md5 without downloading remote bytes") { `performSync resolves tracked memo from head metadata md5 without downloading remote bytes`() }

        test("performSync heads metadata only upload target before overwrite during reconcile fast path") { `performSync heads metadata only upload target before overwrite during reconcile fast path`() }

        test("performSync trusts full snapshot when deleting local file after remote disappearance") { `performSync trusts full snapshot when deleting local file after remote disappearance`() }

        test("repository sync discovers externally added remote file from reconcile page while baseline index is fresh") { `repository sync discovers externally added remote file from reconcile page while baseline index is fresh`() }

        test("repository sync can miss cold external add during fast sync but finds it after reconcile") { `repository sync can miss cold external add during fast sync but finds it after reconcile`() }

        test("repository sync rotates incremental reconcile across bucket prefixes") { `repository sync rotates incremental reconcile across bucket prefixes`() }

        test("repository sync covers all legacy prefixes once without degrading into continuous scans") { `repository sync covers all legacy prefixes once without degrading into continuous scans`() }

        test("performSync streams full reconcile pages into remote index before next page loads") { `performSync streams full reconcile pages into remote index before next page loads`() }
    }


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
    private lateinit var remoteIndexStore: InMemoryS3RemoteIndexStore

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
        coEvery { memoSynchronizer.refreshImportedSync(any()) } returns Unit
        coEvery { localMediaSyncStore.listFiles(any()) } returns emptyMap()

        protocolStateStore = InMemoryS3SyncProtocolStateStore()
        journalStore = InMemoryS3LocalChangeJournalStore()
        remoteIndexStore = InMemoryS3RemoteIndexStore()
    }

    private fun `performSync skips remote probing when cached index is fresh and journal is empty`() =
        runTest {
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastSuccessfulSyncAt = System.currentTimeMillis(),
                    lastFullRemoteScanAt = System.currentTimeMillis(),
                    indexedLocalFileCount = 1,
                    indexedRemoteFileCount = 1,
                ),
            )
            val client =
                ProbeS3Client(
                    onList = { throw AssertionError("fresh cached sync should not list remote objects") },
                    onGetObjectMetadata = {
                        throw AssertionError("fresh cached sync should not head any remote object")
                    },
                )
            val executor = createExecutor(client = client, metadataDao = ExecutorRecordingMetadataDao())

            val result = executor.performSync()

            result shouldBe S3SyncResult.Success(message = "S3 already up to date", outcomes = emptyList())
            client.listCalls shouldBe 0
            client.headCalls shouldBe 0
        }

    private fun `performSync uploads local journal change from cached index without full remote scan`() =
        runTest {
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastSuccessfulSyncAt = System.currentTimeMillis(),
                    lastFullRemoteScanAt = System.currentTimeMillis(),
                    indexedLocalFileCount = 0,
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
            coEvery {
                markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "note.md")
            } returns "# note"
            val client =
                ProbeS3Client(
                    onList = { throw AssertionError("local-only fast path should not list remote objects") },
                    onGetObjectMetadata = {
                        throw AssertionError("local-only fast path should not head manifest or remote objects")
                    },
                )
            val metadataDao = ExecutorRecordingMetadataDao()
            val executor = createExecutor(client = client, metadataDao = metadataDao)

            val result = executor.performSync()

            val success = result as S3SyncResult.Success
            success.message shouldBe "S3 sync completed"
            success.outcomes.map { it.direction to it.reason } shouldBe listOf(S3SyncDirection.UPLOAD to S3SyncReason.LOCAL_ONLY)
            client.putKeys shouldBe listOf("lomo/memo/note.md")
            client.listCalls shouldBe 0
            client.headCalls shouldBe 0
            withClue("journal should be drained after successful upload") { (journalStore.read().isEmpty()).shouldBeTrue() }
            metadataDao.paths() shouldBe listOf("lomo/memo/note.md")
        }

    private fun `performSync uploads cached remote change from fresh index without head when conditional writes are supported`() =
        runTest {
            val path = "lomo/memo/note.md"
            every { dataStore.s3EndpointUrl } returns flowOf("https://s3.amazonaws.com")
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastSuccessfulSyncAt = System.currentTimeMillis(),
                    lastFastSyncAt = System.currentTimeMillis(),
                    lastReconcileAt = System.currentTimeMillis(),
                    lastFullRemoteScanAt = System.currentTimeMillis(),
                    indexedLocalFileCount = 1,
                    indexedRemoteFileCount = 1,
                    scanEpoch = 31L,
                ),
            )
            remoteIndexStore.upsert(
                listOf(
                    S3RemoteIndexEntry(
                        relativePath = path,
                        remotePath = path,
                        etag = "etag-old",
                        remoteLastModified = 10L,
                        size = 1L,
                        lastSeenAt = 10L,
                        lastVerifiedAt = 10L,
                        scanBucket = S3_SCAN_BUCKET_MEMO,
                        scanEpoch = 31L,
                    ),
                ),
            )
            journalStore.upsert(
                S3LocalChangeJournalEntry(
                    id = "MEMO:note.md",
                    kind = S3LocalChangeKind.MEMO,
                    filename = "note.md",
                    changeType = S3LocalChangeType.UPSERT,
                    updatedAt = 60L,
                ),
            )
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "note.md")
            } returns FileMetadata(filename = "note.md", lastModified = 60L)
            coEvery {
                markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "note.md")
            } returns "# local"
            val metadataDao =
                ExecutorRecordingMetadataDao(
                    initial =
                        listOf(
                            stableMetadata(path = path, eTag = "etag-old", lastModified = 10L),
                        ),
                )
            val client =
                ProbeS3Client(
                    onList = { throw AssertionError("fresh local-only incremental upload should not list remote objects") },
                    onGetObjectMetadata = { key ->
                        throw AssertionError("fresh local-only incremental upload should use If-Match instead of HEAD for $key")
                    },
                )
            val executor = createExecutor(client = client, metadataDao = metadataDao)

            val result = executor.performSync(S3SyncWorkIntent.FAST_ONLY)

            val success = result as S3SyncResult.Success
            success.outcomes.map { it.direction to it.reason } shouldBe listOf(S3SyncDirection.UPLOAD to S3SyncReason.LOCAL_ONLY)
            client.headKeys shouldBe emptyList<String>()
            client.putKeys shouldBe listOf(path)
            client.putIfMatchByKey[path] shouldBe "etag-old"
            client.putIfNoneMatchByKey[path] shouldBe null
            withClue("journal should be drained after successful conditional upload") { (journalStore.read().isEmpty()).shouldBeTrue() }
        }

    private fun `performSync materializes conflict when conditional upload precondition fails`() =
        runTest {
            val path = "lomo/memo/note.md"
            every { dataStore.s3EndpointUrl } returns flowOf("https://s3.amazonaws.com")
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastSuccessfulSyncAt = System.currentTimeMillis(),
                    lastFastSyncAt = System.currentTimeMillis(),
                    lastReconcileAt = System.currentTimeMillis(),
                    lastFullRemoteScanAt = System.currentTimeMillis(),
                    indexedLocalFileCount = 1,
                    indexedRemoteFileCount = 1,
                    scanEpoch = 32L,
                ),
            )
            remoteIndexStore.upsert(
                listOf(
                    S3RemoteIndexEntry(
                        relativePath = path,
                        remotePath = path,
                        etag = "etag-old",
                        remoteLastModified = 10L,
                        size = 1L,
                        lastSeenAt = 10L,
                        lastVerifiedAt = 10L,
                        scanBucket = S3_SCAN_BUCKET_MEMO,
                        scanEpoch = 32L,
                    ),
                ),
            )
            journalStore.upsert(
                S3LocalChangeJournalEntry(
                    id = "MEMO:note.md",
                    kind = S3LocalChangeKind.MEMO,
                    filename = "note.md",
                    changeType = S3LocalChangeType.UPSERT,
                    updatedAt = 60L,
                ),
            )
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "note.md")
            } returns FileMetadata(filename = "note.md", lastModified = 60L)
            coEvery {
                markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "note.md")
            } returns "# local"
            val metadataDao =
                ExecutorRecordingMetadataDao(
                    initial =
                        listOf(
                            stableMetadata(path = path, eTag = "etag-old", lastModified = 10L),
                        ),
                )
            val client =
                ProbeS3Client(
                    onList = { throw AssertionError("conditional conflict path should not list remote objects") },
                    onGetObjectMetadata = { key ->
                        throw AssertionError("conditional conflict path should use If-Match instead of HEAD for $key")
                    },
                    onGetObject = { key ->
                        key shouldBe path
                        S3SmallObjectPayload(
                            key = key,
                            eTag = "etag-new",
                            lastModified = 70L,
                            metadata = emptyMap(),
                            bytes = "# remote".toByteArray(StandardCharsets.UTF_8),
                        )
                    },
                    onPutObject = { _, _, ifMatch, ifNoneMatch ->
                        ifMatch shouldBe "etag-old"
                        ifNoneMatch shouldBe null
                        S3PutObjectResult(eTag = null, conditionalWriteFailed = true)
                    },
                )
            val executor = createExecutor(client = client, metadataDao = metadataDao)

            val result = executor.performSync(S3SyncWorkIntent.FAST_ONLY)

            val conflict = result as S3SyncResult.Conflict
            conflict.conflicts.files.single().relativePath shouldBe path
            client.headKeys shouldBe emptyList<String>()
            client.putKeys shouldBe listOf(path)
            withClue("journal should be retained while conditional conflict is unresolved") { (journalStore.read().isNotEmpty()).shouldBeTrue() }
        }

    private fun `performSync rolls back metadata, remote index, and journal when final protocol commit fails`() =
        runTest {
            val initialProtocolState =
                S3SyncProtocolState(
                    lastSuccessfulSyncAt = System.currentTimeMillis(),
                    lastFullRemoteScanAt = System.currentTimeMillis(),
                    indexedLocalFileCount = 0,
                    indexedRemoteFileCount = 0,
                )
            protocolStateStore.write(initialProtocolState)
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
            coEvery {
                markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "note.md")
            } returns "# note"
            val metadataDao = ExecutorRecordingMetadataDao()
            val failingProtocolStateStore =
                FailingWriteProtocolStateStore(
                    delegate = protocolStateStore,
                    failure = IllegalStateException("protocol write failed"),
                )
            val executor =
                createExecutor(
                    client = ProbeS3Client(),
                    metadataDao = metadataDao,
                    protocolStateStore = failingProtocolStateStore,
                    localChangeJournalStore = journalStore,
                    transactionRunner =
                        rollbackableS3SyncTransactionRunner(
                            metadataDao = metadataDao,
                            protocolStateStore = failingProtocolStateStore,
                            localChangeJournalStore = journalStore,
                            remoteIndexStore = remoteIndexStore,
                        ),
                )

            val result = executor.performSync()

            (result is S3SyncResult.Error).shouldBeTrue()
            metadataDao.paths() shouldBe emptyList<String>()
            (journalStore.read().containsKey("MEMO:note.md")).shouldBeTrue()
            remoteIndexStore.readAllRelativePaths() shouldBe emptyList<String>()
            failingProtocolStateStore.read() shouldBe initialProtocolState
        }

    private fun `performSync falls back to full remote reconciliation when cached index is stale`() =
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
                ProbeS3Client(
                    onList = { emptyList() },
                    onGetObjectMetadata = { null },
                )
            val executor = createExecutor(client = client, metadataDao = ExecutorRecordingMetadataDao())

            val result = executor.performSync()

            result shouldBe S3SyncResult.Success(message = "S3 already up to date", outcomes = emptyList())
            client.listPageCalls shouldBe 3
            client.listCalls shouldBe 3
            client.headCalls shouldBe 0
        }

    private fun `performSync verifies cached remote deletion candidate without whole bucket scan`() =
        runTest {
            val path = "lomo/memo/note.md"
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastSuccessfulSyncAt = System.currentTimeMillis(),
                    lastFullRemoteScanAt = System.currentTimeMillis(),
                    indexedLocalFileCount = 1,
                    indexedRemoteFileCount = 1,
                ),
            )
            journalStore.upsert(
                S3LocalChangeJournalEntry(
                    id = "MEMO:note.md",
                    kind = S3LocalChangeKind.MEMO,
                    filename = "note.md",
                    changeType = S3LocalChangeType.DELETE,
                    updatedAt = 60L,
                ),
            )
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "note.md")
            } returns null
            val client =
                ProbeS3Client(
                    onList = { throw AssertionError("targeted delete verification should not list the whole bucket") },
                    onGetObjectMetadata = { key ->
                        key shouldBe path
                        S3RemoteObject(key = key, eTag = "etag-1", lastModified = 10L, metadata = emptyMap())
                    },
                )
            val metadataDao =
                ExecutorRecordingMetadataDao(
                    initial =
                        listOf(
                            stableMetadata(path = path, eTag = "etag-1", lastModified = 10L),
                        ),
                )
            val executor = createExecutor(client = client, metadataDao = metadataDao)

            val result = executor.performSync()

            val success = result as S3SyncResult.Success
            success.message shouldBe "S3 sync completed"
            success.outcomes.map { it.direction to it.reason } shouldBe listOf(S3SyncDirection.DELETE_REMOTE to S3SyncReason.LOCAL_DELETED)
            client.headKeys shouldBe listOf(path)
            client.deletedKeys shouldBe listOf(path)
            client.listCalls shouldBe 0
            withClue("journal should be drained after successful remote delete") { (journalStore.read().isEmpty()).shouldBeTrue() }
        }

    private fun `performSync deletes remote journal target from remote index when metadata snapshot is missing`() =
        runTest {
            val path = "lomo/memo/note.md"
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastSuccessfulSyncAt = System.currentTimeMillis(),
                    lastFullRemoteScanAt = System.currentTimeMillis(),
                    indexedLocalFileCount = 1,
                    indexedRemoteFileCount = 1,
                ),
            )
            remoteIndexStore.upsert(
                listOf(
                    S3RemoteIndexEntry(
                        relativePath = path,
                        remotePath = "opaque/remote-note",
                        etag = "etag-1",
                        remoteLastModified = 10L,
                        size = 1L,
                        lastSeenAt = 10L,
                        lastVerifiedAt = 10L,
                        scanBucket = S3_SCAN_BUCKET_MEMO,
                    ),
                ),
            )
            journalStore.upsert(
                S3LocalChangeJournalEntry(
                    id = "MEMO:note.md",
                    kind = S3LocalChangeKind.MEMO,
                    filename = "note.md",
                    changeType = S3LocalChangeType.DELETE,
                    updatedAt = 60L,
                ),
            )
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "note.md")
            } returns null
            val client =
                ProbeS3Client(
                    onList = { throw AssertionError("remote-index fast delete should not list the whole bucket") },
                    onGetObjectMetadata = { key ->
                        key shouldBe "opaque/remote-note"
                        S3RemoteObject(key = key, eTag = "etag-1", lastModified = 10L, metadata = emptyMap())
                    },
                )

            val executor = createExecutor(client = client, metadataDao = ExecutorRecordingMetadataDao())

            val result = executor.performSync(S3SyncWorkIntent.FAST_ONLY)

            val success = result as S3SyncResult.Success
            success.message shouldBe "S3 sync completed"
            success.outcomes.map { it.direction to it.reason } shouldBe listOf(S3SyncDirection.DELETE_REMOTE to S3SyncReason.LOCAL_DELETED)
            client.headKeys shouldBe listOf("opaque/remote-note")
            client.deletedKeys shouldBe listOf("opaque/remote-note")
            withClue("journal should be drained after successful remote delete") { (journalStore.read().isEmpty()).shouldBeTrue() }
        }

    private fun `performSync downloads remotely updated file before applying cached delete intent`() =
        runTest {
            val path = "lomo/memo/note.md"
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastSuccessfulSyncAt = System.currentTimeMillis(),
                    lastFastSyncAt = System.currentTimeMillis(),
                    lastReconcileAt = System.currentTimeMillis(),
                    lastFullRemoteScanAt = System.currentTimeMillis(),
                    indexedLocalFileCount = 1,
                    indexedRemoteFileCount = 1,
                ),
            )
            journalStore.upsert(
                S3LocalChangeJournalEntry(
                    id = "MEMO:note.md",
                    kind = S3LocalChangeKind.MEMO,
                    filename = "note.md",
                    changeType = S3LocalChangeType.DELETE,
                    updatedAt = 60L,
                ),
            )
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "note.md")
            } returnsMany
                listOf(
                    null,
                    FileMetadata(filename = "note.md", lastModified = 20L),
                )
            coEvery {
                markdownStorageDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = "note.md",
                    content = "# remote",
                    append = false,
                    uri = null,
                )
            } returns null
            val client =
                ProbeS3Client(
                    onList = { throw AssertionError("targeted verification should not list the whole bucket") },
                    onGetObjectMetadata = { key ->
                        key shouldBe path
                        S3RemoteObject(key = key, eTag = "etag-remote", lastModified = 20L, metadata = emptyMap())
                    },
                    onGetObject = { key ->
                        key shouldBe path
                        S3SmallObjectPayload(
                            key = key,
                            eTag = "etag-remote",
                            lastModified = 20L,
                            metadata = emptyMap(),
                            bytes = "# remote".toByteArray(StandardCharsets.UTF_8),
                        )
                    },
                )
            val metadataDao =
                ExecutorRecordingMetadataDao(
                    initial =
                        listOf(
                            stableMetadata(path = path, eTag = "etag-1", lastModified = 10L),
                        ),
                )
            val executor = createExecutor(client = client, metadataDao = metadataDao)

            val result = executor.performSync(S3SyncWorkIntent.FAST_ONLY)

            val success = result as S3SyncResult.Success
            success.message shouldBe "S3 sync completed"
            success.outcomes.map { it.direction to it.reason } shouldBe listOf(S3SyncDirection.DOWNLOAD to S3SyncReason.REMOTE_NEWER)
            client.headKeys shouldBe listOf(path)
            client.deletedKeys shouldBe emptyList<String>()
            withClue("journal should be drained after successful download") { (journalStore.read().isEmpty()).shouldBeTrue() }
        }

    private fun `performSync surfaces conflict when upload candidate changed remotely during fast path`() =
        runTest {
            val path = "lomo/memo/note.md"
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastSuccessfulSyncAt = System.currentTimeMillis(),
                    lastFastSyncAt = System.currentTimeMillis(),
                    lastReconcileAt = System.currentTimeMillis(),
                    lastFullRemoteScanAt = System.currentTimeMillis(),
                    indexedLocalFileCount = 1,
                    indexedRemoteFileCount = 1,
                ),
            )
            journalStore.upsert(
                S3LocalChangeJournalEntry(
                    id = "MEMO:note.md",
                    kind = S3LocalChangeKind.MEMO,
                    filename = "note.md",
                    changeType = S3LocalChangeType.UPSERT,
                    updatedAt = 60L,
                ),
            )
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "note.md")
            } returns FileMetadata(filename = "note.md", lastModified = 30L)
            coEvery {
                markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "note.md")
            } returns "# local"
            val client =
                ProbeS3Client(
                    onList = { throw AssertionError("targeted verification should not list the whole bucket") },
                    onGetObjectMetadata = { key ->
                        key shouldBe path
                        S3RemoteObject(key = key, eTag = "etag-remote", lastModified = 20L, metadata = emptyMap())
                    },
                    onGetObject = { key ->
                        key shouldBe path
                        S3SmallObjectPayload(
                            key = key,
                            eTag = "etag-remote",
                            lastModified = 20L,
                            metadata = emptyMap(),
                            bytes = "# remote".toByteArray(StandardCharsets.UTF_8),
                        )
                    },
                )
            val metadataDao =
                ExecutorRecordingMetadataDao(
                    initial =
                        listOf(
                            stableMetadata(path = path, eTag = "etag-1", lastModified = 10L),
                        ),
                )
            val executor = createExecutor(client = client, metadataDao = metadataDao)

            val result = executor.performSync(S3SyncWorkIntent.FAST_ONLY)

            val conflict = result as S3SyncResult.Conflict
            conflict.conflicts.files.size shouldBe 1
            conflict.conflicts.files.single().relativePath shouldBe path
            client.headKeys shouldBe listOf(path)
            client.putKeys shouldBe emptyList<String>()
            val retainedIndexEntry = remoteIndexStore.readByRelativePaths(listOf(path)).single()
            withClue("conflict paths should stay hot for follow-up reconcile") { (retainedIndexEntry.scanPriority > defaultScanPriority(path)).shouldBeTrue() }
            withClue("journal should be retained while conflict is unresolved") { (journalStore.read().isNotEmpty()).shouldBeTrue() }
        }

    private fun `performSync suppresses tracked memo conflict when local and remote content still match baseline`() =
        runTest {
            val path = "lomo/memo/note.md"
            val baseline = "# baseline".toByteArray(StandardCharsets.UTF_8)
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastSuccessfulSyncAt = System.currentTimeMillis(),
                    lastFastSyncAt = System.currentTimeMillis(),
                    lastReconcileAt = System.currentTimeMillis(),
                    lastFullRemoteScanAt = System.currentTimeMillis(),
                    indexedLocalFileCount = 1,
                    indexedRemoteFileCount = 1,
                ),
            )
            journalStore.upsert(
                S3LocalChangeJournalEntry(
                    id = "MEMO:note.md",
                    kind = S3LocalChangeKind.MEMO,
                    filename = "note.md",
                    changeType = S3LocalChangeType.UPSERT,
                    updatedAt = 60L,
                ),
            )
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "note.md")
            } returns FileMetadata(filename = "note.md", lastModified = 60L)
            coEvery {
                markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "note.md")
            } returns baseline.toString(StandardCharsets.UTF_8)
            val client =
                ProbeS3Client(
                    onList = { throw AssertionError("targeted fingerprint verification should not list the whole bucket") },
                    onGetObjectMetadata = { key ->
                        key shouldBe path
                        S3RemoteObject(key = key, eTag = "multipart-2", lastModified = 20L, metadata = emptyMap())
                    },
                    onGetObject = { key ->
                        key shouldBe path
                        S3SmallObjectPayload(
                            key = key,
                            eTag = "multipart-2",
                            lastModified = 20L,
                            metadata = emptyMap(),
                            bytes = baseline,
                        )
                    },
                )
            val metadataDao =
                ExecutorRecordingMetadataDao(
                    initial =
                        listOf(
                            stableMetadata(
                                path = path,
                                eTag = "etag-1",
                                lastModified = 10L,
                                localFingerprint = baseline.md5Hex(),
                            ),
                        ),
                )
            val executor = createExecutor(client = client, metadataDao = metadataDao)

            val result = executor.performSync(S3SyncWorkIntent.FAST_ONLY)

            result shouldBe S3SyncResult.Success(message = "S3 already up to date", outcomes = emptyList())
            client.headKeys shouldBe listOf(path)
            client.getKeys shouldBe listOf(path)
            client.putKeys shouldBe emptyList<String>()
            withClue("journal should be drained after content-equivalent sync") { (journalStore.read().isEmpty()).shouldBeTrue() }
        }

    private fun `performSync uploads tracked memo when remote matches baseline but local fingerprint changed`() =
        runTest {
            val path = "lomo/memo/note.md"
            val baseline = "# baseline".toByteArray(StandardCharsets.UTF_8)
            val localBytes = "# local changed".toByteArray(StandardCharsets.UTF_8)
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastSuccessfulSyncAt = System.currentTimeMillis(),
                    lastFastSyncAt = System.currentTimeMillis(),
                    lastReconcileAt = System.currentTimeMillis(),
                    lastFullRemoteScanAt = System.currentTimeMillis(),
                    indexedLocalFileCount = 1,
                    indexedRemoteFileCount = 1,
                ),
            )
            journalStore.upsert(
                S3LocalChangeJournalEntry(
                    id = "MEMO:note.md",
                    kind = S3LocalChangeKind.MEMO,
                    filename = "note.md",
                    changeType = S3LocalChangeType.UPSERT,
                    updatedAt = 60L,
                ),
            )
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "note.md")
            } returns FileMetadata(filename = "note.md", lastModified = 60L)
            coEvery {
                markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "note.md")
            } returns localBytes.toString(StandardCharsets.UTF_8)
            val client =
                ProbeS3Client(
                    onList = { throw AssertionError("targeted fingerprint verification should not list the whole bucket") },
                    onGetObjectMetadata = { key ->
                        key shouldBe path
                        S3RemoteObject(key = key, eTag = "multipart-2", lastModified = 20L, metadata = emptyMap())
                    },
                    onGetObject = { key ->
                        key shouldBe path
                        S3SmallObjectPayload(
                            key = key,
                            eTag = "multipart-2",
                            lastModified = 20L,
                            metadata = emptyMap(),
                            bytes = baseline,
                        )
                    },
                )
            val metadataDao =
                ExecutorRecordingMetadataDao(
                    initial =
                        listOf(
                            stableMetadata(
                                path = path,
                                eTag = "etag-1",
                                lastModified = 10L,
                                localFingerprint = baseline.md5Hex(),
                            ),
                        ),
                )
            val executor = createExecutor(client = client, metadataDao = metadataDao)

            val result = executor.performSync(S3SyncWorkIntent.FAST_ONLY)

            val success = result as S3SyncResult.Success
            success.message shouldBe "S3 sync completed"
            success.outcomes.map { it.direction to it.reason } shouldBe listOf(S3SyncDirection.UPLOAD to S3SyncReason.LOCAL_NEWER)
            client.headKeys shouldBe listOf(path)
            withClue("content comparison should fetch the tracked remote memo") { (client.getKeys.isNotEmpty()).shouldBeTrue() }
            withClue("content comparison should only fetch the targeted memo") { (client.getKeys.all { it == path }).shouldBeTrue() }
            client.putKeys shouldBe listOf(path)
            metadataDao.getAll().single().localFingerprint shouldBe localBytes.md5Hex()
            withClue("journal should be drained after fingerprint-backed upload") { (journalStore.read().isEmpty()).shouldBeTrue() }
        }

    private fun `performSync resolves tracked memo from head metadata md5 without downloading remote bytes`() =
        runTest {
            val path = "lomo/memo/note.md"
            val baseline = "# baseline".toByteArray(StandardCharsets.UTF_8)
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastSuccessfulSyncAt = System.currentTimeMillis(),
                    lastFastSyncAt = System.currentTimeMillis(),
                    lastReconcileAt = System.currentTimeMillis(),
                    lastFullRemoteScanAt = System.currentTimeMillis(),
                    indexedLocalFileCount = 1,
                    indexedRemoteFileCount = 1,
                ),
            )
            journalStore.upsert(
                S3LocalChangeJournalEntry(
                    id = "MEMO:note.md",
                    kind = S3LocalChangeKind.MEMO,
                    filename = "note.md",
                    changeType = S3LocalChangeType.UPSERT,
                    updatedAt = 60L,
                ),
            )
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "note.md")
            } returns FileMetadata(filename = "note.md", lastModified = 60L)
            coEvery {
                markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "note.md")
            } returns baseline.toString(StandardCharsets.UTF_8)
            val client =
                ProbeS3Client(
                    onList = { throw AssertionError("targeted fingerprint verification should not list the whole bucket") },
                    onGetObjectMetadata = { key ->
                        key shouldBe path
                        S3RemoteObject(
                            key = key,
                            eTag = "multipart-2",
                            lastModified = 20L,
                            metadata = mapOf("md5" to baseline.md5Hex()),
                        )
                    },
                    onGetObject = { key ->
                        throw AssertionError("head metadata md5 should avoid getObject for $key")
                    },
                )
            val metadataDao =
                ExecutorRecordingMetadataDao(
                    initial =
                        listOf(
                            stableMetadata(
                                path = path,
                                eTag = "etag-1",
                                lastModified = 10L,
                                localFingerprint = baseline.md5Hex(),
                            ),
                        ),
                )
            val executor = createExecutor(client = client, metadataDao = metadataDao)

            val result = executor.performSync(S3SyncWorkIntent.FAST_ONLY)

            result shouldBe S3SyncResult.Success(message = "S3 already up to date", outcomes = emptyList())
            client.headKeys shouldBe listOf(path)
            client.getKeys shouldBe emptyList<String>()
            client.putKeys shouldBe emptyList<String>()
        }

    private fun `performSync heads metadata only upload target before overwrite during reconcile fast path`() =
        runTest {
            val path = "lomo/memo/note.md"
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastSuccessfulSyncAt = System.currentTimeMillis(),
                    lastFullRemoteScanAt = System.currentTimeMillis(),
                    indexedLocalFileCount = 1,
                    indexedRemoteFileCount = 1,
                ),
            )
            journalStore.upsert(
                S3LocalChangeJournalEntry(
                    id = "MEMO:note.md",
                    kind = S3LocalChangeKind.MEMO,
                    filename = "note.md",
                    changeType = S3LocalChangeType.UPSERT,
                    updatedAt = 30L,
                ),
            )
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "note.md")
            } returns FileMetadata(filename = "note.md", lastModified = 30L)
            coEvery {
                markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "note.md")
            } returns "# local"
            val metadataDao =
                ExecutorRecordingMetadataDao(
                    initial =
                        listOf(
                            stableMetadata(path = path, eTag = "etag-1", lastModified = 10L),
                        ),
                )
            val client =
                ProbeS3Client(
                    onList = { emptyList() },
                    onGetObjectMetadata = { key ->
                        key shouldBe path
                        S3RemoteObject(
                            key = key,
                            eTag = "etag-1",
                            lastModified = 10L,
                            metadata = emptyMap(),
                        )
                    },
                )
            val executor = createExecutor(client = client, metadataDao = metadataDao)

            val result = executor.performSync(S3SyncWorkIntent.FAST_THEN_RECONCILE)

            val success = result as S3SyncResult.Success
            success.message shouldBe "S3 sync completed"
            success.outcomes.map { it.direction to it.reason } shouldBe listOf(S3SyncDirection.UPLOAD to S3SyncReason.LOCAL_ONLY)
            client.headKeys shouldBe listOf(path)
            client.putKeys shouldBe listOf(path)
        }

    private fun `performSync trusts full snapshot when deleting local file after remote disappearance`() =
        runTest {
            val path = "lomo/memo/note.md"
            coEvery { markdownStorageDataSource.listMetadataIn(MemoDirectoryType.MAIN) } returns
                listOf(FileMetadata(filename = "note.md", lastModified = 10L))
            val client =
                ProbeS3Client(
                    onList = { emptyList() },
                    onGetObjectMetadata = { key ->
                        key shouldBe path
                        S3RemoteObject(key = key, eTag = "etag-1", lastModified = 10L, metadata = emptyMap())
                    },
                )
            val metadataDao =
                ExecutorRecordingMetadataDao(
                    initial =
                        listOf(
                            stableMetadata(path = path, eTag = "etag-1", lastModified = 10L),
                        ),
                )
            val executor = createExecutor(client = client, metadataDao = metadataDao)

            val result = executor.performSync()

            val success = result as S3SyncResult.Success
            success.message shouldBe "S3 sync completed"
            success.outcomes.map { it.direction to it.reason } shouldBe listOf(S3SyncDirection.DELETE_LOCAL to S3SyncReason.REMOTE_DELETED)
            (client.headKeys.isEmpty()).shouldBeTrue()
            coVerify(exactly = 1) { markdownStorageDataSource.deleteFileIn(MemoDirectoryType.MAIN, "note.md") }
        }

    private fun `repository sync discovers externally added remote file from reconcile page while baseline index is fresh`() =
        runTest {
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastSuccessfulSyncAt = System.currentTimeMillis(),
                    lastFullRemoteScanAt = System.currentTimeMillis(),
                    indexedLocalFileCount = 0,
                    indexedRemoteFileCount = 0,
                ),
            )
            val client =
                ProbeS3Client(
                    onList = {
                        listOf(
                            S3RemoteObject(
                                key = "lomo/memo/remote.md",
                                eTag = "etag-remote",
                                lastModified = 70L,
                                metadata = emptyMap(),
                            ),
                        )
                    },
                    onGetObject = { key ->
                        key shouldBe "lomo/memo/remote.md"
                        S3SmallObjectPayload(
                            key = key,
                            eTag = "etag-remote",
                            lastModified = 70L,
                            metadata = emptyMap(),
                            bytes = "# remote".toByteArray(StandardCharsets.UTF_8),
                        )
                    },
                )
            coEvery {
                markdownStorageDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = "remote.md",
                    content = "# remote",
                    append = false,
                    uri = null,
                )
            } returns null
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "remote.md")
            } returns null
            val repository = createOperationRepository(client = client, metadataDao = ExecutorRecordingMetadataDao())

            val result = repository.executeS3Sync(S3SyncWorkIntent.FAST_THEN_RECONCILE)

            val success = result as S3SyncResult.Success
            success.message shouldBe "S3 sync completed"
            success.outcomes.map { it.direction to it.reason } shouldBe listOf(S3SyncDirection.DOWNLOAD to S3SyncReason.REMOTE_ONLY)
            client.listCalls shouldBe 1
        }

    private fun `repository sync can miss cold external add during fast sync but finds it after reconcile`() =
        runTest {
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastSuccessfulSyncAt = System.currentTimeMillis(),
                    lastFullRemoteScanAt = System.currentTimeMillis(),
                    indexedLocalFileCount = 0,
                    indexedRemoteFileCount = 0,
                ),
            )
            val client =
                ProbeS3Client(
                    onList = {
                        listOf(
                            S3RemoteObject(
                                key = "lomo/memo/cold-remote.md",
                                eTag = "etag-cold",
                                lastModified = 71L,
                                metadata = emptyMap(),
                            ),
                        )
                    },
                    onGetObject = { key ->
                        key shouldBe "lomo/memo/cold-remote.md"
                        S3SmallObjectPayload(
                            key = key,
                            eTag = "etag-cold",
                            lastModified = 71L,
                            metadata = emptyMap(),
                            bytes = "# cold remote".toByteArray(StandardCharsets.UTF_8),
                        )
                    },
                )
            coEvery {
                markdownStorageDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = "cold-remote.md",
                    content = "# cold remote",
                    append = false,
                    uri = null,
                )
            } returns null
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "cold-remote.md")
            } returns null
            val repository = createOperationRepository(client = client, metadataDao = ExecutorRecordingMetadataDao())

            val fastOnly = repository.executeS3Sync(S3SyncWorkIntent.FAST_ONLY)
            val reconcile = repository.executeS3Sync(S3SyncWorkIntent.FAST_THEN_RECONCILE)

            fastOnly shouldBe S3SyncResult.Success(message = "S3 already up to date", outcomes = emptyList())
            val reconcileSuccess = reconcile as S3SyncResult.Success
            reconcileSuccess.message shouldBe "S3 sync completed"
            reconcileSuccess.outcomes.map { it.direction to it.reason } shouldBe listOf(S3SyncDirection.DOWNLOAD to S3SyncReason.REMOTE_ONLY)
            withClue("fast sync should not discover the cold remote add") { client.listCalls shouldBe 1 }
        }

    private fun `repository sync rotates incremental reconcile across bucket prefixes`() =
        runTest {
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastSuccessfulSyncAt = System.currentTimeMillis(),
                    lastFullRemoteScanAt = System.currentTimeMillis(),
                    indexedLocalFileCount = 0,
                    indexedRemoteFileCount = 0,
                ),
            )
            val listedPrefixes = mutableListOf<String>()
            val client =
                ProbeS3Client(
                    onList = { throw AssertionError("incremental reconcile should use paged list calls") },
                    onListPage = { prefix, continuationToken, _ ->
                        listedPrefixes += prefix
                        continuationToken shouldBe null
                        S3RemoteListPage(objects = emptyList(), nextContinuationToken = null)
                    },
                )
            val repository = createOperationRepository(client = client, metadataDao = ExecutorRecordingMetadataDao())

            val first = repository.executeS3Sync(S3SyncWorkIntent.FAST_THEN_RECONCILE)
            val second = repository.executeS3Sync(S3SyncWorkIntent.FAST_THEN_RECONCILE)

            (first is S3SyncResult.Success).shouldBeTrue()
            (second is S3SyncResult.Success).shouldBeTrue()
            listedPrefixes shouldBe listOf("lomo/memo/", "lomo/images/")
            (protocolStateStore.read()?.remoteScanCursor?.contains("voice") == true).shouldBeTrue()
        }

    private fun `repository sync covers all legacy prefixes once without degrading into continuous scans`() =
        runTest {
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastSuccessfulSyncAt = System.currentTimeMillis(),
                    lastFullRemoteScanAt = System.currentTimeMillis(),
                    indexedLocalFileCount = 0,
                    indexedRemoteFileCount = 0,
                ),
            )
            val listedPrefixes = mutableListOf<String>()
            val client =
                ProbeS3Client(
                    onList = { throw AssertionError("incremental reconcile should use paged list calls") },
                    onListPage = { prefix, continuationToken, _ ->
                        listedPrefixes += prefix
                        continuationToken shouldBe null
                        S3RemoteListPage(objects = emptyList(), nextContinuationToken = null)
                    },
                )
            val repository = createOperationRepository(client = client, metadataDao = ExecutorRecordingMetadataDao())

            repeat(3) {
                val result = repository.executeS3Sync(S3SyncWorkIntent.FAST_THEN_RECONCILE)
                (result is S3SyncResult.Success).shouldBeTrue()
            }
            val steadyState = repository.executeS3Sync(S3SyncWorkIntent.FAST_THEN_RECONCILE)

            listedPrefixes shouldBe listOf("lomo/memo/", "lomo/images/", "lomo/voice/")
            (steadyState is S3SyncResult.Success).shouldBeTrue()
        }

    private fun `performSync streams full reconcile pages into remote index before next page loads`() =
        runTest {
            val firstPageIndexed = CompletableDeferred<Unit>()
            val remoteIndexStore =
                SignalingRemoteIndexStore(
                    delegate = InMemoryS3RemoteIndexStore(),
                    onUpsert = { entries ->
                        if (
                            entries.any { entry ->
                                entry.relativePath.endsWith("first.md") || entry.remotePath.endsWith("first.md")
                            } &&
                            !firstPageIndexed.isCompleted
                        ) {
                            firstPageIndexed.complete(Unit)
                        }
                    },
                )
            val client =
                ProbeS3Client(
                    onList = { throw AssertionError("full reconcile should use paged list calls") },
                    onListPage = { prefix, continuationToken, _ ->
                        if (prefix != "lomo/memo/") {
                            continuationToken shouldBe null
                            S3RemoteListPage(
                                objects = emptyList(),
                                nextContinuationToken = null,
                            )
                        } else {
                            when (continuationToken) {
                                null ->
                                    S3RemoteListPage(
                                        objects =
                                            listOf(
                                                S3RemoteObject(
                                                    key = "lomo/memo/first.md",
                                                    eTag = "etag-first",
                                                    lastModified = 10L,
                                                    metadata = emptyMap(),
                                                ),
                                            ),
                                        nextContinuationToken = "page-2",
                                    )

                                "page-2" -> {
                                    kotlinx.coroutines.withTimeout(500) { firstPageIndexed.await() }
                                    S3RemoteListPage(
                                        objects =
                                            listOf(
                                                S3RemoteObject(
                                                    key = "lomo/memo/second.md",
                                                    eTag = "etag-second",
                                                    lastModified = 20L,
                                                    metadata = emptyMap(),
                                                ),
                                            ),
                                        nextContinuationToken = null,
                                    )
                                }

                                else -> throw AssertionError("Unexpected continuation token $continuationToken")
                            }
                        }
                    },
                    onGetObject = { key ->
                        S3SmallObjectPayload(
                            key = key,
                            eTag =
                                when (key) {
                                    "lomo/memo/first.md" -> "etag-first"
                                    "lomo/memo/second.md" -> "etag-second"
                                    else -> throw AssertionError("Unexpected getObject for $key")
                                },
                            lastModified =
                                when (key) {
                                    "lomo/memo/first.md" -> 10L
                                    "lomo/memo/second.md" -> 20L
                                    else -> throw AssertionError("Unexpected getObject for $key")
                                },
                            metadata = emptyMap(),
                            bytes = "# remote".toByteArray(StandardCharsets.UTF_8),
                        )
                    },
                )
            coEvery {
                markdownStorageDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = any(),
                    content = any(),
                    append = false,
                    uri = null,
                )
            } returns null
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, any())
            } returns null
            val executor =
                createExecutor(
                    client = client,
                    metadataDao = ExecutorRecordingMetadataDao(),
                    remoteIndexStore = remoteIndexStore,
                )

            val result = executor.performSync(S3SyncWorkIntent.FULL_RECONCILE)

            val success = result as S3SyncResult.Success
            success.message shouldBe "S3 sync completed"
            (firstPageIndexed.isCompleted).shouldBeTrue()
            remoteIndexStore.readAll().map(S3RemoteIndexEntry::relativePath).toSet() shouldBe setOf("lomo/memo/first.md", "lomo/memo/second.md")
        }

    private fun createExecutor(
        client: ProbeS3Client,
        metadataDao: ExecutorRecordingMetadataDao,
        protocolStateStore: S3SyncProtocolStateStore = this.protocolStateStore,
        localChangeJournalStore: S3LocalChangeJournalStore = journalStore,
        remoteIndexStore: S3RemoteIndexStore = this.remoteIndexStore,
        transactionRunner: S3SyncTransactionRunner = NoOpS3SyncTransactionRunner,
    ): S3SyncExecutor {
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
                performanceTuner = DisabledSyncPerformanceTuner,
                transactionRunner = transactionRunner,
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
            protocolStateStore = protocolStateStore,
            localChangeJournalStore = localChangeJournalStore,
            remoteIndexStore = remoteIndexStore,
            remoteShardStateStore = DisabledS3RemoteShardStateStore,
            pendingConflictStore = InMemoryPendingSyncConflictStore(),
            pendingReviewStore = InMemoryPendingSyncReviewStore(),
        )
    }

    private fun createOperationRepository(
        client: ProbeS3Client,
        metadataDao: ExecutorRecordingMetadataDao,
    ): S3SyncOperationRepositoryImpl {
        val executor = createExecutor(client = client, metadataDao = metadataDao)
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
                performanceTuner = DisabledSyncPerformanceTuner,
                transactionRunner = NoOpS3SyncTransactionRunner,
            )
        val stateHolder = runtime.stateHolder
        val encodingSupport = S3SyncEncodingSupport()
        return S3SyncOperationRepositoryImpl(
            syncExecutor = executor,
            statusTester =
                S3SyncStatusTester(
                    runtime = runtime,
                    support = S3SyncRepositorySupport(runtime),
                    encodingSupport = encodingSupport,
                    fileBridge = S3SyncFileBridge(runtime, encodingSupport),
                    protocolStateStore = protocolStateStore,
                    localChangeJournalStore = journalStore,
                    remoteIndexStore = remoteIndexStore,
                ),
            refreshPolicyPlanner =
                object : S3RefreshSyncPolicyPlanner {
                    override suspend fun planRefreshSync(signal: com.lomo.data.sync.SyncRefreshSignal) =
                        com.lomo.data.sync.SyncWorkDecision(
                            foregroundWork =
                                com.lomo.data.sync.SyncForegroundWork(
                                    backend = SyncBackendType.S3,
                                    trigger = com.lomo.data.sync.SyncWorkTrigger.REFRESH,
                                    payload = com.lomo.data.sync.SyncWorkPayload.ProviderParameters(mapOf(S3_SYNC_WORK_INTENT_PARAMETER to S3SyncWorkIntent.FAST_ONLY.name)),
                                ),
                        )
                },
            scheduledWorkEnqueuer =
                object : S3ScheduledSyncWorkEnqueuer {
                    override suspend fun enqueue(work: List<com.lomo.data.sync.SyncScheduledWork>) = Unit
                },
            stateHolder = stateHolder,
            pendingConflictStore = InMemoryPendingSyncConflictStore(),
        )
    }

    private fun stableMetadata(
        path: String,
        eTag: String,
        lastModified: Long,
        localFingerprint: String? = null,
    ) = S3SyncMetadataEntity(
        relativePath = path,
        remotePath = path,
        etag = eTag,
        remoteLastModified = lastModified,
        localLastModified = lastModified,
        localFingerprint = localFingerprint,
        lastSyncedAt = lastModified,
        lastResolvedDirection = S3SyncMetadataEntity.NONE,
        lastResolvedReason = S3SyncMetadataEntity.UNCHANGED,
    )
}

private class ExecutorRecordingMetadataDao(
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
        entities.forEach { entity ->
            entries[entity.relativePath] = entity
        }
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

    suspend fun snapshot(): List<S3SyncMetadataEntity> = getAll()

    suspend fun restore(snapshot: List<S3SyncMetadataEntity>) {
        clearAll()
        upsertAll(snapshot)
    }
}

private class ProbeS3Client(
    private val onList: suspend () -> List<S3RemoteObject> = { emptyList() },
    private val onListPage: (suspend (String, String?, Int) -> S3RemoteListPage)? = null,
    private val onGetObjectMetadata: suspend (String) -> S3RemoteObject? = {
        throw AssertionError("Unexpected headObject for $it")
    },
    private val onGetObject: suspend (String) -> S3SmallObjectPayload = {
        throw AssertionError("Unexpected getObject for $it")
    },
    private val onPutObject: suspend (String, ByteArray, String?, String?) -> S3PutObjectResult = { _, _, _, _ ->
        S3PutObjectResult(eTag = "etag-uploaded")
    },
    private val onDeleteObject: suspend (String) -> Unit = {},
) : LomoS3Client {
    private val listCallsValue = AtomicInteger(0)
    private val listPageCallsValue = AtomicInteger(0)
    private val headCallsValue = AtomicInteger(0)
    private val getObjectCallsValue = AtomicInteger(0)
    val headKeys = mutableListOf<String>()
    val getKeys = mutableListOf<String>()
    val putKeys = mutableListOf<String>()
    val deletedKeys = mutableListOf<String>()
    val putIfMatchByKey = linkedMapOf<String, String?>()
    val putIfNoneMatchByKey = linkedMapOf<String, String?>()

    val listCalls: Int
        get() = listCallsValue.get()

    val listPageCalls: Int
        get() = listPageCallsValue.get()

    val headCalls: Int
        get() = headCallsValue.get()

    val getObjectCalls: Int
        get() = getObjectCallsValue.get()

    override suspend fun verifyAccess(prefix: String) = Unit

    override suspend fun getObjectMetadata(key: String): S3RemoteObject? {
        headCallsValue.incrementAndGet()
        headKeys += key
        return onGetObjectMetadata(key)
    }

    override suspend fun list(
        prefix: String,
        maxKeys: Int?,
    ): List<S3RemoteObject> {
        listCallsValue.incrementAndGet()
        return onList()
    }

    override suspend fun listPage(
        prefix: String,
        continuationToken: String?,
        maxKeys: Int,
    ): S3RemoteListPage {
        listPageCallsValue.incrementAndGet()
        return onListPage?.invoke(prefix, continuationToken, maxKeys)
            ?: if (continuationToken == null) {
                listCallsValue.incrementAndGet()
                S3RemoteListPage(objects = onList(), nextContinuationToken = null)
            } else {
                S3RemoteListPage(objects = emptyList(), nextContinuationToken = null)
            }
    }

    override suspend fun getSmallObject(key: String): S3SmallObjectPayload {
        getObjectCallsValue.incrementAndGet()
        getKeys += key
        return onGetObject(key)
    }

    override suspend fun putSmallObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
        ifMatch: String?,
        ifNoneMatch: String?,
    ): S3PutObjectResult {
        putKeys += key
        putIfMatchByKey[key] = ifMatch
        putIfNoneMatchByKey[key] = ifNoneMatch
        return onPutObject(key, bytes, ifMatch, ifNoneMatch)
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
        onDeleteObject(key)
    }

    override fun close() = Unit
}

private class SignalingRemoteIndexStore(
    private val delegate: S3RemoteIndexStore,
    private val onUpsert: (Collection<S3RemoteIndexEntry>) -> Unit,
) : S3RemoteIndexStore by delegate {
    override suspend fun upsert(entries: Collection<S3RemoteIndexEntry>) {
        delegate.upsert(entries)
        onUpsert(entries)
    }
}

private class FailingWriteProtocolStateStore(
    private val delegate: S3SyncProtocolStateStore,
    private val failure: Throwable,
) : S3SyncProtocolStateStore by delegate {
    private var failWrites: Boolean = true

    override suspend fun write(state: S3SyncProtocolState) {
        if (failWrites) {
            throw failure
        }
        delegate.write(state)
    }

    suspend fun restoreSnapshot(state: S3SyncProtocolState?) {
        failWrites = false
        delegate.clear()
        if (state != null) {
            delegate.write(state)
        }
        failWrites = true
    }
}

private fun rollbackableS3SyncTransactionRunner(
    metadataDao: ExecutorRecordingMetadataDao,
    protocolStateStore: S3SyncProtocolStateStore,
    localChangeJournalStore: S3LocalChangeJournalStore,
    remoteIndexStore: S3RemoteIndexStore,
): S3SyncTransactionRunner =
    object : S3SyncTransactionRunner {
        override suspend fun <T> runInTransaction(block: suspend () -> T): T {
            val metadataSnapshot = metadataDao.snapshot()
            val protocolSnapshot = protocolStateStore.read()
            val journalSnapshot = localChangeJournalStore.read().values.toList()
            val remoteIndexSnapshot =
                remoteIndexStore.readByRelativePaths(remoteIndexStore.readAllRelativePaths())
            return try {
                block()
            } catch (error: Throwable) {
                metadataDao.restore(metadataSnapshot)
                if (protocolStateStore is FailingWriteProtocolStateStore) {
                    protocolStateStore.restoreSnapshot(protocolSnapshot)
                } else {
                    protocolStateStore.clear()
                    if (protocolSnapshot != null) {
                        protocolStateStore.write(protocolSnapshot)
                    }
                }
                localChangeJournalStore.clear()
                journalSnapshot.forEach { entry ->
                    localChangeJournalStore.upsert(entry)
                }
                remoteIndexStore.clear()
                remoteIndexStore.upsert(remoteIndexSnapshot)
                throw error
            }
        }
    }
