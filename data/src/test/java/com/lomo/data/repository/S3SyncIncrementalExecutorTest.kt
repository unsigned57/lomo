package com.lomo.data.repository

import com.lomo.data.local.dao.S3SyncMetadataDao
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
import com.lomo.data.source.FileMetadata
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncReason
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.S3SyncScanPolicy
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: S3SyncExecutor
 * - Behavior focus: manifest-free S3 sync should reuse the local remote index for fast paths, reconcile with a full remote listing only when the cached index is stale, and verify destructive local-delete candidates without scanning the whole bucket.
 * - Observable outcomes: returned S3SyncResult, remote list/head invocation counts, uploaded/deleted remote keys, and local journal drain behavior.
 * - Red phase: Fails before the fix because sync still probes the retired manifest protocol and cannot execute fast paths or targeted destructive verification without touching manifest-specific remote objects.
 * - Excludes: AWS SDK transport internals, Room generated code, WorkManager scheduling, and UI rendering.
 */
class S3SyncIncrementalExecutorTest {
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
        coEvery { memoSynchronizer.refreshImportedSync(any()) } returns Unit
        coEvery { localMediaSyncStore.listFiles(any()) } returns emptyMap()

        protocolStateStore = InMemoryS3SyncProtocolStateStore()
        journalStore = InMemoryS3LocalChangeJournalStore()
        remoteIndexStore = InMemoryS3RemoteIndexStore()
    }

    @Test
    fun `performSync skips remote probing when cached index is fresh and journal is empty`() =
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

            assertEquals(
                S3SyncResult.Success(message = "S3 already up to date", outcomes = emptyList()),
                result,
            )
            assertEquals(0, client.listCalls)
            assertEquals(0, client.headCalls)
        }

    @Test
    fun `performSync uploads local journal change from cached index without full remote scan`() =
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
            assertEquals("S3 sync completed", success.message)
            assertEquals(
                listOf(S3SyncDirection.UPLOAD to S3SyncReason.LOCAL_ONLY),
                success.outcomes.map { it.direction to it.reason },
            )
            assertEquals(listOf("lomo/memo/note.md"), client.putKeys)
            assertEquals(0, client.listCalls)
            assertEquals(0, client.headCalls)
            assertTrue("journal should be drained after successful upload", journalStore.read().isEmpty())
            assertEquals(listOf("lomo/memo/note.md"), metadataDao.paths())
        }

    @Test
    fun `performSync falls back to full remote reconciliation when cached index is stale`() =
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

            assertEquals(
                S3SyncResult.Success(message = "S3 already up to date", outcomes = emptyList()),
                result,
            )
            assertEquals(3, client.listPageCalls)
            assertEquals(3, client.listCalls)
            assertEquals(0, client.headCalls)
        }

    @Test
    fun `performSync verifies cached remote deletion candidate without whole bucket scan`() =
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
                        assertEquals(path, key)
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
            assertEquals("S3 sync completed", success.message)
            assertEquals(
                listOf(S3SyncDirection.DELETE_REMOTE to S3SyncReason.LOCAL_DELETED),
                success.outcomes.map { it.direction to it.reason },
            )
            assertEquals(listOf(path), client.headKeys)
            assertEquals(listOf(path), client.deletedKeys)
            assertEquals(0, client.listCalls)
            assertTrue("journal should be drained after successful remote delete", journalStore.read().isEmpty())
        }

    @Test
    fun `performSync deletes remote journal target from remote index when metadata snapshot is missing`() =
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
                        assertEquals("opaque/remote-note", key)
                        S3RemoteObject(key = key, eTag = "etag-1", lastModified = 10L, metadata = emptyMap())
                    },
                )

            val executor = createExecutor(client = client, metadataDao = ExecutorRecordingMetadataDao())

            val result = executor.performSync(S3SyncScanPolicy.FAST_ONLY)

            val success = result as S3SyncResult.Success
            assertEquals("S3 sync completed", success.message)
            assertEquals(
                listOf(S3SyncDirection.DELETE_REMOTE to S3SyncReason.LOCAL_DELETED),
                success.outcomes.map { it.direction to it.reason },
            )
            assertEquals(listOf("opaque/remote-note"), client.headKeys)
            assertEquals(listOf("opaque/remote-note"), client.deletedKeys)
            assertTrue("journal should be drained after successful remote delete", journalStore.read().isEmpty())
        }

    @Test
    fun `performSync downloads remotely updated file before applying cached delete intent`() =
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
                        assertEquals(path, key)
                        S3RemoteObject(key = key, eTag = "etag-remote", lastModified = 20L, metadata = emptyMap())
                    },
                    onGetObject = { key ->
                        assertEquals(path, key)
                        S3RemoteObjectPayload(
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

            val result = executor.performSync(S3SyncScanPolicy.FAST_ONLY)

            val success = result as S3SyncResult.Success
            assertEquals("S3 sync completed", success.message)
            assertEquals(
                listOf(S3SyncDirection.DOWNLOAD to S3SyncReason.REMOTE_NEWER),
                success.outcomes.map { it.direction to it.reason },
            )
            assertEquals(listOf(path), client.headKeys)
            assertEquals(emptyList<String>(), client.deletedKeys)
            assertTrue("journal should be drained after successful download", journalStore.read().isEmpty())
        }

    @Test
    fun `performSync surfaces conflict when upload candidate changed remotely during fast path`() =
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
                        assertEquals(path, key)
                        S3RemoteObject(key = key, eTag = "etag-remote", lastModified = 20L, metadata = emptyMap())
                    },
                    onGetObject = { key ->
                        assertEquals(path, key)
                        S3RemoteObjectPayload(
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

            val result = executor.performSync(S3SyncScanPolicy.FAST_ONLY)

            val conflict = result as S3SyncResult.Conflict
            assertEquals(1, conflict.conflicts.files.size)
            assertEquals(path, conflict.conflicts.files.single().relativePath)
            assertEquals(listOf(path), client.headKeys)
            assertEquals(emptyList<String>(), client.putKeys)
            assertTrue("journal should be retained while conflict is unresolved", journalStore.read().isNotEmpty())
        }

    @Test
    fun `performSync rechecks remote object before deleting local file from full snapshot`() =
        runTest {
            val path = "lomo/memo/note.md"
            coEvery { markdownStorageDataSource.listMetadataIn(MemoDirectoryType.MAIN) } returns
                listOf(FileMetadata(filename = "note.md", lastModified = 10L))
            val client =
                ProbeS3Client(
                    onList = { emptyList() },
                    onGetObjectMetadata = { key ->
                        assertEquals(path, key)
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
            assertEquals("S3 sync completed", success.message)
            assertEquals(listOf(path), client.headKeys)
            coVerify(exactly = 0) { markdownStorageDataSource.deleteFileIn(MemoDirectoryType.MAIN, "note.md") }
        }

    @Test
    fun `repository sync discovers externally added remote file from reconcile page while baseline index is fresh`() =
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
                        assertEquals("lomo/memo/remote.md", key)
                        S3RemoteObjectPayload(
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

            val result = repository.sync(S3SyncScanPolicy.FAST_THEN_RECONCILE)

            val success = result as S3SyncResult.Success
            assertEquals("S3 sync completed", success.message)
            assertEquals(
                listOf(S3SyncDirection.DOWNLOAD to S3SyncReason.REMOTE_ONLY),
                success.outcomes.map { it.direction to it.reason },
            )
            assertEquals(1, client.listCalls)
        }

    @Test
    fun `repository sync can miss cold external add during fast sync but finds it after reconcile`() =
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
                        assertEquals("lomo/memo/cold-remote.md", key)
                        S3RemoteObjectPayload(
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

            val fastOnly = repository.sync(S3SyncScanPolicy.FAST_ONLY)
            val reconcile = repository.sync(S3SyncScanPolicy.FAST_THEN_RECONCILE)

            assertEquals(
                S3SyncResult.Success(message = "S3 already up to date", outcomes = emptyList()),
                fastOnly,
            )
            val reconcileSuccess = reconcile as S3SyncResult.Success
            assertEquals("S3 sync completed", reconcileSuccess.message)
            assertEquals(
                listOf(S3SyncDirection.DOWNLOAD to S3SyncReason.REMOTE_ONLY),
                reconcileSuccess.outcomes.map { it.direction to it.reason },
            )
            assertEquals("fast sync should not discover the cold remote add", 1, client.listCalls)
        }

    @Test
    fun `repository sync rotates incremental reconcile across bucket prefixes`() =
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
                        assertEquals(null, continuationToken)
                        S3RemoteListPage(objects = emptyList(), nextContinuationToken = null)
                    },
                )
            val repository = createOperationRepository(client = client, metadataDao = ExecutorRecordingMetadataDao())

            val first = repository.sync(S3SyncScanPolicy.FAST_THEN_RECONCILE)
            val second = repository.sync(S3SyncScanPolicy.FAST_THEN_RECONCILE)

            assertTrue(first is S3SyncResult.Success)
            assertTrue(second is S3SyncResult.Success)
            assertEquals(listOf("lomo/memo/", "lomo/images/"), listedPrefixes)
            assertTrue(protocolStateStore.read()?.remoteScanCursor?.contains("voice") == true)
        }

    @Test
    fun `repository sync covers all legacy prefixes once without degrading into continuous scans`() =
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
                        assertEquals(null, continuationToken)
                        S3RemoteListPage(objects = emptyList(), nextContinuationToken = null)
                    },
                )
            val repository = createOperationRepository(client = client, metadataDao = ExecutorRecordingMetadataDao())

            repeat(3) {
                val result = repository.sync(S3SyncScanPolicy.FAST_THEN_RECONCILE)
                assertTrue(result is S3SyncResult.Success)
            }
            val steadyState = repository.sync(S3SyncScanPolicy.FAST_THEN_RECONCILE)

            assertEquals(
                listOf("lomo/memo/", "lomo/images/", "lomo/voice/"),
                listedPrefixes,
            )
            assertTrue(steadyState is S3SyncResult.Success)
        }

    @Test
    fun `performSync streams full reconcile pages into remote index before next page loads`() =
        runTest {
            val firstPageIndexed = CompletableDeferred<Unit>()
            val remoteIndexStore =
                SignalingRemoteIndexStore(
                    delegate = InMemoryS3RemoteIndexStore(),
                    onUpsert = { entries ->
                        if (entries.any { it.relativePath == "lomo/memo/first.md" } && !firstPageIndexed.isCompleted) {
                            firstPageIndexed.complete(Unit)
                        }
                    },
                )
            val client =
                ProbeS3Client(
                    onList = { throw AssertionError("full reconcile should use paged list calls") },
                    onListPage = { prefix, continuationToken, _ ->
                        if (prefix != "lomo/memo/") {
                            assertEquals(null, continuationToken)
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
                        S3RemoteObjectPayload(
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
            val executor =
                createExecutor(
                    client = client,
                    metadataDao = ExecutorRecordingMetadataDao(),
                    remoteIndexStore = remoteIndexStore,
                )

            val result = executor.performSync(S3SyncScanPolicy.FULL_RECONCILE)

            val success = result as S3SyncResult.Success
            assertEquals("S3 sync completed", success.message)
            assertTrue(firstPageIndexed.isCompleted)
            assertEquals(
                setOf("lomo/memo/first.md", "lomo/memo/second.md"),
                remoteIndexStore.readAll().map(S3RemoteIndexEntry::relativePath).toSet(),
            )
        }

    private fun createExecutor(
        client: ProbeS3Client,
        metadataDao: ExecutorRecordingMetadataDao,
        remoteIndexStore: S3RemoteIndexStore = this.remoteIndexStore,
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
            )
        val encodingSupport = S3SyncEncodingSupport()
        val fileBridge = S3SyncFileBridge(runtime, encodingSupport)
        return S3SyncExecutor(
            runtime = runtime,
            support = S3SyncRepositorySupport(runtime),
            encodingSupport = encodingSupport,
            fileBridge = fileBridge,
            actionApplier = S3SyncActionApplier(runtime, encodingSupport, fileBridge),
            protocolStateStore = protocolStateStore,
            localChangeJournalStore = journalStore,
            remoteIndexStore = remoteIndexStore,
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
            )
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
                ),
        )
    }

    private fun stableMetadata(
        path: String,
        eTag: String,
        lastModified: Long,
    ) = S3SyncMetadataEntity(
        relativePath = path,
        remotePath = path,
        etag = eTag,
        remoteLastModified = lastModified,
        localLastModified = lastModified,
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
}

private class ProbeS3Client(
    private val onList: suspend () -> List<S3RemoteObject> = { emptyList() },
    private val onListPage: (suspend (String, String?, Int) -> S3RemoteListPage)? = null,
    private val onGetObjectMetadata: suspend (String) -> S3RemoteObject? = {
        throw AssertionError("Unexpected headObject for $it")
    },
    private val onGetObject: suspend (String) -> S3RemoteObjectPayload = {
        throw AssertionError("Unexpected getObject for $it")
    },
    private val onPutObject: suspend (String, ByteArray) -> S3PutObjectResult = { _, _ ->
        S3PutObjectResult(eTag = "etag-uploaded")
    },
    private val onDeleteObject: suspend (String) -> Unit = {},
) : LomoS3Client {
    private val listCallsValue = AtomicInteger(0)
    private val listPageCallsValue = AtomicInteger(0)
    private val headCallsValue = AtomicInteger(0)
    val headKeys = mutableListOf<String>()
    val putKeys = mutableListOf<String>()
    val deletedKeys = mutableListOf<String>()

    val listCalls: Int
        get() = listCallsValue.get()

    val listPageCalls: Int
        get() = listPageCallsValue.get()

    val headCalls: Int
        get() = headCallsValue.get()

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

    override suspend fun getObject(key: String): S3RemoteObjectPayload = onGetObject(key)

    override suspend fun putObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
    ): S3PutObjectResult {
        putKeys += key
        return onPutObject(key, bytes)
    }

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
