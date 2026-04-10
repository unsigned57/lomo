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
import com.lomo.data.s3.S3RemoteObject
import com.lomo.data.s3.S3RemoteObjectPayload
import com.lomo.data.source.FileMetadata
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.webdav.LocalMediaSyncStore
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: S3ConflictResolver
 * - Behavior focus: conflict resolution should reuse indexed remote paths for targeted uploads/downloads without whole-bucket scans and must still map remote fetch failures into S3 error results.
 * - Observable outcomes: S3SyncResult type, remote list/get/put targets, metadata persistence, and post-resolution refresh state.
 * - Red phase: Fails before the fix because conflict resolution still probes the retired manifest path before applying a KEEP_LOCAL or KEEP_REMOTE choice, so targeted resolution cannot succeed without the old private protocol.
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
    private lateinit var markdownStorageDataSource: MarkdownStorageDataSource

    @MockK(relaxed = true)
    private lateinit var localMediaSyncStore: LocalMediaSyncStore

    @MockK(relaxed = true)
    private lateinit var memoSynchronizer: MemoSynchronizer

    private lateinit var stateHolder: S3SyncStateHolder
    private lateinit var support: S3SyncRepositorySupport
    private lateinit var fileBridge: S3SyncFileBridge

    @Before
    fun setUp() {
        MockKAnnotations.init(this)

        every { dataStore.s3SyncEnabled } returns flowOf(true)
        every { dataStore.s3EndpointUrl } returns flowOf("https://s3.example.com")
        every { dataStore.s3Region } returns flowOf("us-east-1")
        every { dataStore.s3Bucket } returns flowOf("bucket")
        every { dataStore.s3Prefix } returns flowOf("prefix")
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
        coEvery { memoSynchronizer.refreshImportedSync() } returns Unit
        coEvery { localMediaSyncStore.listFiles(any()) } returns emptyMap()

        stateHolder = S3SyncStateHolder()
    }

    @Test
    fun `resolveConflicts keeps remote using indexed remote path without remote listing`() =
        runTest {
            val path = "lomo/memo/note.md"
            val remotePath = "prefix/opaque-note"
            val client =
                ConflictProbeS3Client(
                    onGetObject = { key ->
                        assertEquals(remotePath, key)
                        S3RemoteObjectPayload(
                            key = key,
                            eTag = "etag-remote",
                            lastModified = 40L,
                            metadata = emptyMap(),
                            bytes = "# remote".toByteArray(),
                        )
                    },
                )
            val metadataDao =
                ConflictMetadataDao(
                    initial = listOf(stableMetadata(path = path, remotePath = remotePath)),
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
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "note.md")
            } returns FileMetadata(filename = "note.md", lastModified = 40L)
            val resolver = createResolver(client = client, metadataDao = metadataDao)

            val result =
                resolver.resolveConflicts(
                    resolution =
                        SyncConflictResolution(
                            perFileChoices = mapOf(path to SyncConflictResolutionChoice.KEEP_REMOTE),
                        ),
                    conflictSet = conflictSet(path = path),
                )

            assertEquals(S3SyncResult.Success("Conflicts resolved"), result)
            assertEquals(listOf(remotePath), client.getObjectKeys)
            assertEquals(0, client.listCalls)
            coVerify(exactly = 1) { memoSynchronizer.refreshImportedSync() }
            assertEquals(remotePath, metadataDao.require(path).remotePath)
            assertTrue(stateHolder.state.value is S3SyncState.Success)
        }

    @Test
    fun `resolveConflicts keeps remote using remote index path when metadata snapshot is missing`() =
        runTest {
            val path = "lomo/memo/note.md"
            val remotePath = "prefix/opaque-note"
            val client =
                ConflictProbeS3Client(
                    onGetObject = { key ->
                        assertEquals(remotePath, key)
                        S3RemoteObjectPayload(
                            key = key,
                            eTag = "etag-remote",
                            lastModified = 40L,
                            metadata = emptyMap(),
                            bytes = "# remote".toByteArray(),
                        )
                    },
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
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "note.md")
            } returns FileMetadata(filename = "note.md", lastModified = 40L)
            val remoteIndexStore = InMemoryS3RemoteIndexStore()
            remoteIndexStore.upsert(
                listOf(
                    S3RemoteIndexEntry(
                        relativePath = path,
                        remotePath = remotePath,
                        etag = "etag-remote",
                        remoteLastModified = 40L,
                        size = 1L,
                        lastSeenAt = 40L,
                        lastVerifiedAt = 40L,
                        scanBucket = S3_SCAN_BUCKET_MEMO,
                    ),
                ),
            )
            val resolver =
                createResolver(
                    client = client,
                    metadataDao = ConflictMetadataDao(),
                    remoteIndexStore = remoteIndexStore,
                )

            val result =
                resolver.resolveConflicts(
                    resolution =
                        SyncConflictResolution(
                            perFileChoices = mapOf(path to SyncConflictResolutionChoice.KEEP_REMOTE),
                        ),
                    conflictSet = conflictSet(path = path),
                )

            assertEquals(S3SyncResult.Success("Conflicts resolved"), result)
            assertEquals(listOf(remotePath), client.getObjectKeys)
            assertEquals(0, client.listCalls)
            assertTrue(stateHolder.state.value is S3SyncState.Success)
        }

    @Test
    fun `resolveConflicts keeps local using indexed remote path without remote listing`() =
        runTest {
            val path = "lomo/memo/note.md"
            val remotePath = "prefix/opaque-note"
            val client =
                ConflictProbeS3Client(
                    onPutObject = { key, _ ->
                        assertEquals(remotePath, key)
                        S3PutObjectResult(eTag = "etag-uploaded")
                    },
                )
            val metadataDao =
                ConflictMetadataDao(
                    initial = listOf(stableMetadata(path = path, remotePath = remotePath)),
                )
            coEvery {
                markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "note.md")
            } returns "# local"
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "note.md")
            } returns FileMetadata(filename = "note.md", lastModified = 50L)
            val resolver = createResolver(client = client, metadataDao = metadataDao)

            val result =
                resolver.resolveConflicts(
                    resolution =
                        SyncConflictResolution(
                            perFileChoices = mapOf(path to SyncConflictResolutionChoice.KEEP_LOCAL),
                        ),
                    conflictSet = conflictSet(path = path),
                )

            assertEquals(S3SyncResult.Success("Conflicts resolved"), result)
            assertEquals(listOf(remotePath), client.putKeys)
            assertEquals(0, client.listCalls)
            coVerify(exactly = 1) { memoSynchronizer.refreshImportedSync() }
            assertEquals(remotePath, metadataDao.require(path).remotePath)
        }

    @Test
    fun `resolveConflicts MERGE_TEXT writes merged memo locally and uploads it without remote listing`() =
        runTest {
            val path = "lomo/memo/note.md"
            val remotePath = "prefix/opaque-note"
            val merged = "start\nlocal\nmiddle\nremote\nend"
            val client =
                ConflictProbeS3Client(
                    onPutObject = { key, bytes ->
                        assertEquals(remotePath, key)
                        assertEquals(merged, bytes.toString(Charsets.UTF_8))
                        S3PutObjectResult(eTag = "etag-merged")
                    },
                )
            val metadataDao =
                ConflictMetadataDao(
                    initial = listOf(stableMetadata(path = path, remotePath = remotePath)),
                )
            coEvery {
                markdownStorageDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = "note.md",
                    content = merged,
                    append = false,
                    uri = null,
                )
            } returns null
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "note.md")
            } returns FileMetadata(filename = "note.md", lastModified = 60L)
            val resolver = createResolver(client = client, metadataDao = metadataDao)
            val conflictSet =
                conflictSet(
                    path = path,
                    localContent = "start\nlocal\nmiddle\nend",
                    remoteContent = "start\nmiddle\nremote\nend",
                )

            val result =
                resolver.resolveConflicts(
                    resolution =
                        SyncConflictResolution(
                            perFileChoices = mapOf(path to SyncConflictResolutionChoice.MERGE_TEXT),
                        ),
                    conflictSet = conflictSet,
                )

            assertEquals(S3SyncResult.Success("Conflicts resolved"), result)
            assertEquals(listOf(remotePath), client.putKeys)
            assertEquals(0, client.listCalls)
            coVerify(exactly = 1) {
                markdownStorageDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = "note.md",
                    content = merged,
                    append = false,
                    uri = null,
                )
            }
            assertEquals(remotePath, metadataDao.require(path).remotePath)
            assertTrue(stateHolder.state.value is S3SyncState.Success)
        }

    @Test
    fun `resolveConflicts rolls back metadata, remote index, and journal when final protocol commit fails`() =
        runTest {
            val path = "lomo/memo/note.md"
            val metadataDao = ConflictMetadataDao()
            val protocolStateStore =
                ConflictFailingWriteProtocolStateStore(
                    delegate =
                        InMemoryS3SyncProtocolStateStore().apply {
                            write(
                                S3SyncProtocolState(
                                    lastSuccessfulSyncAt = 10L,
                                    lastFullRemoteScanAt = 10L,
                                    indexedLocalFileCount = 0,
                                    indexedRemoteFileCount = 0,
                                ),
                            )
                        },
                    failure = IllegalStateException("protocol write failed"),
                )
            val localChangeJournalStore =
                InMemoryS3LocalChangeJournalStore().apply {
                    upsert(
                        S3LocalChangeJournalEntry(
                            id = "MEMO:note.md",
                            kind = S3LocalChangeKind.MEMO,
                            filename = "note.md",
                            changeType = S3LocalChangeType.UPSERT,
                            updatedAt = 50L,
                        ),
                    )
                }
            val remoteIndexStore = InMemoryS3RemoteIndexStore()
            val client = ConflictProbeS3Client()
            coEvery {
                markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "note.md")
            } returns "# local"
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "note.md")
            } returns FileMetadata(filename = "note.md", lastModified = 50L)
            val resolver =
                createResolver(
                    client = client,
                    metadataDao = metadataDao,
                    protocolStateStore = protocolStateStore,
                    localChangeJournalStore = localChangeJournalStore,
                    remoteIndexStore = remoteIndexStore,
                    transactionRunner =
                        rollbackableConflictResolutionTransactionRunner(
                            metadataDao = metadataDao,
                            protocolStateStore = protocolStateStore,
                            localChangeJournalStore = localChangeJournalStore,
                            remoteIndexStore = remoteIndexStore,
                        ),
                )

            val result =
                resolver.resolveConflicts(
                    resolution =
                        SyncConflictResolution(
                            perFileChoices = mapOf(path to SyncConflictResolutionChoice.KEEP_LOCAL),
                        ),
                    conflictSet = conflictSet(path = path),
                )

            assertTrue(result is S3SyncResult.Error)
            assertTrue(metadataDao.getAll().isEmpty())
            assertTrue(localChangeJournalStore.read().containsKey("MEMO:note.md"))
            assertTrue(remoteIndexStore.readAllRelativePaths().isEmpty())
        }

    @Test
    fun `resolveConflicts maps remote read failure without bucket scan`() =
        runTest {
            val path = "lomo/memo/note.md"
            val remotePath = "prefix/opaque-note"
            val client =
                ConflictProbeS3Client(
                    onGetObject = { key ->
                        assertEquals(remotePath, key)
                        throw IllegalStateException("bucket failed")
                    },
                )
            val resolver =
                createResolver(
                    client = client,
                    metadataDao =
                        ConflictMetadataDao(
                            initial = listOf(stableMetadata(path = path, remotePath = remotePath)),
                        ),
                )

            val result =
                resolver.resolveConflicts(
                    resolution =
                        SyncConflictResolution(
                            perFileChoices = mapOf(path to SyncConflictResolutionChoice.KEEP_REMOTE),
                        ),
                    conflictSet = conflictSet(path = path),
                )

            val error = result as S3SyncResult.Error
            assertEquals(S3SyncErrorCode.BUCKET_ACCESS_FAILED, error.code)
            assertEquals("bucket failed", error.message)
            assertEquals(0, client.listCalls)
            assertTrue(stateHolder.state.value is S3SyncState.Error)
        }

    @Test
    fun `resolveConflicts keeps skipped files pending and returns conflict state`() =
        runTest {
            val keptPath = "lomo/memo/kept.md"
            val skippedPath = "lomo/memo/skipped.md"
            val remotePath = "prefix/opaque-kept"
            val client =
                ConflictProbeS3Client(
                    onPutObject = { key, _ ->
                        assertEquals(remotePath, key)
                        S3PutObjectResult(eTag = "etag-uploaded")
                    },
                )
            val metadataDao =
                ConflictMetadataDao(
                    initial = listOf(stableMetadata(path = keptPath, remotePath = remotePath)),
                )
            val pendingStore = InMemoryPendingSyncConflictStore()
            coEvery {
                markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "kept.md")
            } returns "# local"
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "kept.md")
            } returns FileMetadata(filename = "kept.md", lastModified = 50L)
            val resolver = createResolver(client = client, metadataDao = metadataDao, pendingStore = pendingStore)
            val conflictSet =
                SyncConflictSet(
                    source = SyncBackendType.S3,
                    files =
                        listOf(
                            SyncConflictFile(
                                relativePath = keptPath,
                                localContent = "# local",
                                remoteContent = "# remote",
                                isBinary = false,
                            ),
                            SyncConflictFile(
                                relativePath = skippedPath,
                                localContent = "left",
                                remoteContent = "right",
                                isBinary = false,
                            ),
                        ),
                    timestamp = 1L,
                )

            val result =
                resolver.resolveConflicts(
                    resolution =
                        SyncConflictResolution(
                            perFileChoices =
                                mapOf(
                                    keptPath to SyncConflictResolutionChoice.KEEP_LOCAL,
                                    skippedPath to SyncConflictResolutionChoice.SKIP_FOR_NOW,
                                ),
                        ),
                    conflictSet = conflictSet,
                )

            assertEquals(
                S3SyncResult.Conflict(
                    message = "Pending conflicts remain",
                    conflicts =
                        conflictSet.copy(
                            files = listOf(conflictSet.files[1]),
                        ),
                ),
                result,
            )
            assertEquals(listOf(remotePath), client.putKeys)
            assertEquals(
                conflictSet.copy(files = listOf(conflictSet.files[1])),
                pendingStore.read(SyncBackendType.S3),
            )
            assertEquals(
                S3SyncState.ConflictDetected(conflictSet.copy(files = listOf(conflictSet.files[1]))),
                stateHolder.state.value,
            )
        }

    private fun createResolver(
        client: ConflictProbeS3Client,
        metadataDao: ConflictMetadataDao,
        protocolStateStore: S3SyncProtocolStateStore = InMemoryS3SyncProtocolStateStore(),
        localChangeJournalStore: S3LocalChangeJournalStore = InMemoryS3LocalChangeJournalStore(),
        remoteIndexStore: S3RemoteIndexStore = DisabledS3RemoteIndexStore,
        pendingStore: PendingSyncConflictStore = InMemoryPendingSyncConflictStore(),
        transactionRunner: S3SyncTransactionRunner = NoOpS3SyncTransactionRunner,
    ): S3ConflictResolver {
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
                stateHolder = stateHolder,
                transactionRunner = transactionRunner,
            )
        val encodingSupport = S3SyncEncodingSupport()
        fileBridge = S3SyncFileBridge(runtime, encodingSupport)
        support = S3SyncRepositorySupport(runtime)
        return S3ConflictResolver(
            runtime = runtime,
            support = support,
            encodingSupport = encodingSupport,
            fileBridge = fileBridge,
            protocolStateStore = protocolStateStore,
            localChangeJournalStore = localChangeJournalStore,
            remoteIndexStore = remoteIndexStore,
            pendingConflictStore = pendingStore,
        )
    }

    private fun stableMetadata(
        path: String,
        remotePath: String,
    ) = S3SyncMetadataEntity(
        relativePath = path,
        remotePath = remotePath,
        etag = "etag-1",
        remoteLastModified = 10L,
        localLastModified = 10L,
        lastSyncedAt = 10L,
        lastResolvedDirection = S3SyncMetadataEntity.NONE,
        lastResolvedReason = S3SyncMetadataEntity.UNCHANGED,
    )

    private fun conflictSet(
        path: String,
        localContent: String = "# local",
        remoteContent: String = "# remote",
    ): SyncConflictSet =
        SyncConflictSet(
            source = SyncBackendType.S3,
            files =
                listOf(
                    SyncConflictFile(
                        relativePath = path,
                        localContent = localContent,
                        remoteContent = remoteContent,
                        isBinary = false,
                    ),
                ),
            timestamp = 1L,
        )
}

private class ConflictMetadataDao(
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

    fun require(path: String): S3SyncMetadataEntity = requireNotNull(entries[path])

    suspend fun snapshot(): List<S3SyncMetadataEntity> = getAll()

    suspend fun restore(snapshot: List<S3SyncMetadataEntity>) {
        clearAll()
        upsertAll(snapshot)
    }
}

private class ConflictProbeS3Client(
    private val onGetObject: suspend (String) -> S3RemoteObjectPayload = {
        throw AssertionError("Unexpected getObject for $it")
    },
    private val onPutObject: suspend (String, ByteArray) -> S3PutObjectResult = { _, _ ->
        S3PutObjectResult(eTag = "etag-uploaded")
    },
) : LomoS3Client {
    var listCalls: Int = 0
    val getObjectKeys = mutableListOf<String>()
    val putKeys = mutableListOf<String>()

    override suspend fun verifyAccess(prefix: String) = Unit

    override suspend fun getObjectMetadata(key: String): S3RemoteObject? = null

    override suspend fun list(
        prefix: String,
        maxKeys: Int?,
    ): List<S3RemoteObject> {
        listCalls += 1
        throw AssertionError("Conflict resolution should not list remote objects")
    }

    override suspend fun getObject(key: String): S3RemoteObjectPayload {
        getObjectKeys += key
        return onGetObject(key)
    }

    override suspend fun putObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
    ): S3PutObjectResult {
        putKeys += key
        return onPutObject(key, bytes)
    }

    override suspend fun deleteObject(key: String) =
        throw AssertionError("Conflict resolution should not delete remote objects")

    override fun close() = Unit
}

private class ConflictFailingWriteProtocolStateStore(
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

private fun rollbackableConflictResolutionTransactionRunner(
    metadataDao: ConflictMetadataDao,
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
                if (protocolStateStore is ConflictFailingWriteProtocolStateStore) {
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
