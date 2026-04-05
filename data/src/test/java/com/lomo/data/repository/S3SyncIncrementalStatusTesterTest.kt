package com.lomo.data.repository

import com.lomo.data.local.dao.S3SyncMetadataDao
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
import com.lomo.domain.model.S3SyncStatus
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/*
 * Test Contract:
 * - Unit under test: S3SyncStatusTester
 * - Behavior focus: status lookups should reuse manifest revision probing plus local journal fast paths after bootstrap instead of re-running full local and remote discovery, including SAF vault-root mode.
 * - Observable outcomes: returned S3SyncStatus, local discovery call counts, manifest body download behavior, and remote list call counts.
 * - Red phase: Fails before the fix because getStatus always downloads the manifest body and still performs full local discovery for vault-root paths even when the incremental protocol state is current.
 * - Excludes: AWS transport behavior, metadata persistence mutations, and UI rendering.
 */
class S3SyncIncrementalStatusTesterTest {
    @MockK(relaxed = true)
    private lateinit var dataStore: LomoDataStore

    @MockK(relaxed = true)
    private lateinit var credentialStore: S3CredentialStore

    @MockK(relaxed = true)
    private lateinit var markdownStorageDataSource: MarkdownStorageDataSource

    @MockK(relaxed = true)
    private lateinit var localMediaSyncStore: LocalMediaSyncStore

    @MockK(relaxed = true)
    private lateinit var memoSynchronizer: MemoSynchronizer

    private lateinit var protocolStateStore: InMemoryS3SyncProtocolStateStore
    private lateinit var journalStore: InMemoryS3LocalChangeJournalStore
    private lateinit var manifestStore: DefaultS3RemoteManifestStore

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
        every { dataStore.s3LastSyncTime } returns flowOf(0L)
        every { credentialStore.getAccessKeyId() } returns "access"
        every { credentialStore.getSecretAccessKey() } returns "secret"
        every { credentialStore.getSessionToken() } returns null
        every { credentialStore.getEncryptionPassword() } returns null
        every { credentialStore.getEncryptionPassword2() } returns null
        coEvery { localMediaSyncStore.listFiles(any()) } returns emptyMap()

        protocolStateStore = InMemoryS3SyncProtocolStateStore()
        journalStore = InMemoryS3LocalChangeJournalStore()
        manifestStore = DefaultS3RemoteManifestStore(S3SyncEncodingSupport())
    }

    @Test
    fun `getStatus skips full discovery when manifest revision is unchanged and journal is empty`() =
        runTest {
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastManifestRevision = 9L,
                    indexedLocalFileCount = 2,
                    indexedRemoteFileCount = 2,
                ),
            )
            val manifest =
                S3RemoteManifest(
                    revision = 9L,
                    generatedAt = 90L,
                    entries =
                        listOf(
                            S3RemoteManifestEntry("lomo/memo/one.md", "lomo/memo/one.md", "e1", 10L),
                            S3RemoteManifestEntry("lomo/memo/two.md", "lomo/memo/two.md", "e2", 20L),
                        ),
                )
            val tester =
                createTester(
                    client = IncrementalStatusClient(manifestStore = manifestStore, manifest = manifest),
                    metadataDao = IncrementalStatusMetadataDao(),
                )

            val status = tester.getStatus()

            assertEquals(
                S3SyncStatus(remoteFileCount = 2, localFileCount = 2, pendingChanges = 0, lastSyncTime = null),
                status,
            )
            coVerify(exactly = 0) { markdownStorageDataSource.listMetadataIn(MemoDirectoryType.MAIN) }
            coVerify(exactly = 0) { localMediaSyncStore.listFiles(any()) }
        }

    @Test
    fun `getStatus counts journaled memo change without full discovery`() =
        runTest {
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastManifestRevision = 4L,
                    indexedLocalFileCount = 1,
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
                markdownStorageDataSource.getFileMetadataIn(
                    MemoDirectoryType.MAIN,
                    "note.md",
                )
            } returns FileMetadata("note.md", 50L)
            val tester =
                createTester(
                    client =
                        IncrementalStatusClient(
                            manifestStore = manifestStore,
                            manifest = S3RemoteManifest(revision = 4L, generatedAt = 40L, entries = emptyList()),
                        ),
                    metadataDao = IncrementalStatusMetadataDao(),
                )

            val status = tester.getStatus()

            assertEquals(
                S3SyncStatus(remoteFileCount = 0, localFileCount = 1, pendingChanges = 1, lastSyncTime = null),
                status,
            )
            coVerify(exactly = 0) { markdownStorageDataSource.listMetadataIn(MemoDirectoryType.MAIN) }
            coVerify(exactly = 0) { localMediaSyncStore.listFiles(any()) }
        }

    @Test
    fun `getStatus does not full-scan metadata for local-only incremental status`() =
        runTest {
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastManifestRevision = 5L,
                    indexedLocalFileCount = 1,
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
                markdownStorageDataSource.getFileMetadataIn(
                    MemoDirectoryType.MAIN,
                    "note.md",
                )
            } returns FileMetadata("note.md", 50L)
            val tester =
                createTester(
                    client =
                        IncrementalStatusClient(
                            manifestStore = manifestStore,
                            manifest = S3RemoteManifest(revision = 5L, generatedAt = 50L, entries = emptyList()),
                        ),
                    metadataDao =
                        FailingStatusFullScanMetadataDao(
                            errorMessage = "full metadata scan should be skipped",
                        ),
                )

            val status = tester.getStatus()

            assertEquals(
                S3SyncStatus(remoteFileCount = 0, localFileCount = 1, pendingChanges = 1, lastSyncTime = null),
                status,
            )
        }

    @Test
    fun `getStatus uses manifest metadata revision for local-only incremental status without downloading manifest`() =
        runTest {
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastManifestRevision = 6L,
                    indexedLocalFileCount = 1,
                    indexedRemoteFileCount = 0,
                    lastSuccessfulSyncAt = System.currentTimeMillis(),
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
                markdownStorageDataSource.getFileMetadataIn(
                    MemoDirectoryType.MAIN,
                    "note.md",
                )
            } returns FileMetadata("note.md", 50L)
            val tester =
                createTester(
                    client =
                        IncrementalStatusClient(
                            manifestStore = manifestStore,
                            manifest = S3RemoteManifest(revision = 6L, generatedAt = 60L, entries = emptyList()),
                            throwOnManifestRead = true,
                            manifestRevisionOverride = 6L,
                        ),
                    metadataDao = IncrementalStatusMetadataDao(),
                )

            val status = tester.getStatus()

            assertEquals(
                S3SyncStatus(remoteFileCount = 0, localFileCount = 1, pendingChanges = 1, lastSyncTime = null),
                status,
            )
        }

    @Test
    fun `getStatus applies remote manifest delta without full metadata entity scan`() =
        runTest {
            val managedPath = "lomo/memo/note.md"
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastManifestRevision = 17L,
                    indexedLocalFileCount = 1,
                    indexedRemoteFileCount = 1,
                ),
            )
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(
                    MemoDirectoryType.MAIN,
                    "note.md",
                )
            } returns FileMetadata("note.md", 30L)
            val tester =
                createTester(
                    client =
                        IncrementalStatusClient(
                            manifestStore = manifestStore,
                            manifest =
                                S3RemoteManifest(
                                    revision = 18L,
                                    generatedAt = 180L,
                                    entries =
                                        listOf(
                                            S3RemoteManifestEntry(
                                                relativePath = managedPath,
                                                remotePath = managedPath,
                                                etag = "etag-new",
                                                remoteLastModified = 40L,
                                            ),
                                        ),
                                ),
                        ),
                    metadataDao =
                        FailingStatusFullScanMetadataDao(
                            initial =
                                listOf(
                                    S3SyncMetadataEntity(
                                        relativePath = managedPath,
                                        remotePath = managedPath,
                                        etag = "etag-old",
                                        remoteLastModified = 30L,
                                        localLastModified = 30L,
                                        lastSyncedAt = 30L,
                                        lastResolvedDirection = "NONE",
                                        lastResolvedReason = "UNCHANGED",
                                    ),
                                ),
                            errorMessage = "full metadata entity scan should be skipped",
                        ),
                )

            val status = tester.getStatus()

            assertEquals(
                S3SyncStatus(remoteFileCount = 1, localFileCount = 1, pendingChanges = 1, lastSyncTime = null),
                status,
            )
        }

    @Test
    fun `getStatus skips saf vault root full discovery when revision metadata is unchanged`() =
        runTest {
            every { dataStore.s3LocalSyncDirectory } returns flowOf("content://tree/primary%3AVault")
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastManifestRevision = 7L,
                    indexedLocalFileCount = 2,
                    indexedRemoteFileCount = 2,
                    lastSuccessfulSyncAt = System.currentTimeMillis(),
                ),
            )
            val tester =
                createTester(
                    client =
                        IncrementalStatusClient(
                            manifestStore = manifestStore,
                            manifest = S3RemoteManifest(revision = 7L, generatedAt = 70L, entries = emptyList()),
                            throwOnManifestRead = true,
                            manifestRevisionOverride = 7L,
                        ),
                    metadataDao = IncrementalStatusMetadataDao(),
                    safTreeAccess =
                        object : S3SafTreeAccess {
                            override suspend fun listFiles(rootUriString: String): List<S3SafTreeFile> =
                                error("SAF full discovery should be skipped")

                            override suspend fun getFile(
                                rootUriString: String,
                                relativePath: String,
                            ): S3SafTreeFile? = null

                            override suspend fun readBytes(
                                rootUriString: String,
                                relativePath: String,
                            ): ByteArray? = null

                            override suspend fun readText(
                                rootUriString: String,
                                relativePath: String,
                            ): String? = null

                            override suspend fun writeBytes(
                                rootUriString: String,
                                relativePath: String,
                                bytes: ByteArray,
                            ) = Unit

                            override suspend fun deleteFile(
                                rootUriString: String,
                                relativePath: String,
                            ) = Unit
                        },
                )

            val status = tester.getStatus()

            assertEquals(
                S3SyncStatus(remoteFileCount = 2, localFileCount = 2, pendingChanges = 0, lastSyncTime = null),
                status,
            )
        }

    @Test
    fun `getStatus reuses vault root journal path without reporting duplicate change`() =
        runTest {
            val vaultRoot = Files.createTempDirectory("s3-status-vault-root").toFile()
            val localFile = vaultRoot.resolve("note.md")
            localFile.writeText("# note")
            org.junit.Assert.assertTrue(localFile.setLastModified(50L))
            every { dataStore.s3LocalSyncDirectory } returns flowOf(vaultRoot.absolutePath)
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastManifestRevision = 15L,
                    indexedLocalFileCount = 1,
                    indexedRemoteFileCount = 1,
                    lastSuccessfulSyncAt = System.currentTimeMillis(),
                ),
            )
            journalStore.upsert(
                S3LocalChangeJournalEntry(
                    id = "MEMO:note.md",
                    kind = S3LocalChangeKind.MEMO,
                    filename = "note.md",
                    changeType = S3LocalChangeType.UPSERT,
                    updatedAt = 150L,
                ),
            )
            val tester =
                createTester(
                    client =
                        IncrementalStatusClient(
                            manifestStore = manifestStore,
                            manifest =
                                S3RemoteManifest(
                                    revision = 15L,
                                    generatedAt = 150L,
                                    entries =
                                        listOf(
                                            S3RemoteManifestEntry(
                                                relativePath = "note.md",
                                                remotePath = "note.md",
                                                etag = "etag-note",
                                                remoteLastModified = 50L,
                                            ),
                                        ),
                                ),
                            throwOnManifestRead = true,
                            manifestRevisionOverride = 15L,
                        ),
                    metadataDao =
                        IncrementalStatusMetadataDao(
                            initial =
                                listOf(
                                    S3SyncMetadataEntity(
                                        relativePath = "note.md",
                                        remotePath = "note.md",
                                        etag = "etag-note",
                                        remoteLastModified = 50L,
                                        localLastModified = 50L,
                                        lastSyncedAt = 50L,
                                        lastResolvedDirection = "NONE",
                                        lastResolvedReason = "UNCHANGED",
                                    ),
                                ),
                        ),
                )

            val status = tester.getStatus()

            assertEquals(
                S3SyncStatus(remoteFileCount = 1, localFileCount = 1, pendingChanges = 0, lastSyncTime = null),
                status,
            )
        }

    @Test
    fun `getStatus detects generic markdown change under explicit vault root without waiting for audit`() =
        runTest {
            val vaultRoot = Files.createTempDirectory("s3-status-vault-generic").toFile()
            val memoRoot = vaultRoot.resolve("journal").also { it.mkdirs() }
            val imageRoot = vaultRoot.resolve("asset").also { it.mkdirs() }
            val voiceRoot = vaultRoot.resolve("voice").also { it.mkdirs() }
            val boardFile = vaultRoot.resolve("pages.kanban/board.md").apply {
                parentFile?.mkdirs()
                writeText("# board")
            }
            org.junit.Assert.assertTrue(boardFile.setLastModified(60L))
            every { dataStore.s3LocalSyncDirectory } returns flowOf(vaultRoot.absolutePath)
            every { dataStore.rootDirectory } returns flowOf(memoRoot.absolutePath)
            every { dataStore.imageDirectory } returns flowOf(imageRoot.absolutePath)
            every { dataStore.voiceDirectory } returns flowOf(voiceRoot.absolutePath)
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastManifestRevision = 17L,
                    indexedLocalFileCount = 1,
                    indexedRemoteFileCount = 0,
                    lastSuccessfulSyncAt = System.currentTimeMillis(),
                ),
            )
            val tester =
                createTester(
                    client =
                        IncrementalStatusClient(
                            manifestStore = manifestStore,
                            manifest = S3RemoteManifest(revision = 17L, generatedAt = 170L, entries = emptyList()),
                            throwOnManifestRead = true,
                            manifestRevisionOverride = 17L,
                        ),
                    metadataDao = IncrementalStatusMetadataDao(),
                )

            val status = tester.getStatus()

            assertEquals(
                S3SyncStatus(remoteFileCount = 0, localFileCount = 1, pendingChanges = 1, lastSyncTime = null),
                status,
            )
        }

    private fun createTester(
        client: LomoS3Client,
        metadataDao: S3SyncMetadataDao,
        safTreeAccess: S3SafTreeAccess = UnsupportedS3SafTreeAccess,
    ): S3SyncStatusTester {
        val runtime =
            S3SyncRepositoryContext(
                dataStore = dataStore,
                credentialStore = credentialStore,
                clientFactory = LomoS3ClientFactory { client },
                markdownStorageDataSource = markdownStorageDataSource,
                localMediaSyncStore = localMediaSyncStore,
                metadataDao = metadataDao,
                memoSynchronizer = memoSynchronizer,
                planner = S3SyncPlanner(timestampToleranceMs = 0L),
                stateHolder = S3SyncStateHolder(),
            )
        return S3SyncStatusTester(
            runtime = runtime,
            support = S3SyncRepositorySupport(runtime),
            encodingSupport = S3SyncEncodingSupport(),
            fileBridge = S3SyncFileBridge(runtime, S3SyncEncodingSupport(), safTreeAccess),
            protocolStateStore = protocolStateStore,
            localChangeJournalStore = journalStore,
            remoteManifestStore = manifestStore,
        )
    }
}

private class IncrementalStatusClient(
    private val manifestStore: S3RemoteManifestStore,
    private val manifest: S3RemoteManifest,
    private val throwOnManifestRead: Boolean = false,
    private val manifestRevisionOverride: Long? = null,
) : LomoS3Client {
    private val json = Json
    var listCallCount: Int = 0
        private set

    override suspend fun verifyAccess(prefix: String) = Unit

    override suspend fun getObjectMetadata(key: String): S3RemoteObject? {
        if (key != manifestStore.manifestKey(incrementalStatusConfig())) {
            return null
        }
        val revision = manifestRevisionOverride ?: manifest.revision
        return S3RemoteObject(
            key = key,
            eTag = "manifest-$revision",
            lastModified = manifest.generatedAt,
            metadata =
                mapOf(
                    S3_MANIFEST_REVISION_METADATA_KEY to revision.toString(),
                    S3_MANIFEST_GENERATED_AT_METADATA_KEY to manifest.generatedAt.toString(),
                ),
        )
    }

    override suspend fun list(
        prefix: String,
        maxKeys: Int?,
    ): List<S3RemoteObject> {
        listCallCount += 1
        return emptyList()
    }

    override suspend fun getObject(key: String): S3RemoteObjectPayload =
        if (key == manifestStore.manifestKey(incrementalStatusConfig())) {
            if (throwOnManifestRead) {
                error("manifest body download should be skipped")
            }
            S3RemoteObjectPayload(
                key = key,
                eTag = "manifest-${manifest.revision}",
                lastModified = manifest.generatedAt,
                metadata = emptyMap(),
                bytes = json.encodeToString(S3RemoteManifest.serializer(), manifest).toByteArray(),
            )
        } else {
            error("Unexpected getObject($key)")
        }

    override suspend fun putObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
    ): S3PutObjectResult = error("putObject should not be used in status tests")

    override suspend fun deleteObject(key: String) = Unit

    override fun close() = Unit
}

private class IncrementalStatusMetadataDao(
    private val initial: List<S3SyncMetadataEntity> = emptyList(),
) : S3SyncMetadataDao {
    private val entities = linkedMapOf<String, S3SyncMetadataEntity>()

    init {
        initial.forEach { entity ->
            entities[entity.relativePath] = entity
        }
    }

    override suspend fun getAll(): List<S3SyncMetadataEntity> = entities.values.toList()

    override suspend fun getAllRemoteMetadataSnapshots(): List<S3SyncRemoteMetadataSnapshot> =
        entities.values.map { entity ->
            S3SyncRemoteMetadataSnapshot(
                relativePath = entity.relativePath,
                remotePath = entity.remotePath,
                etag = entity.etag,
                remoteLastModified = entity.remoteLastModified,
            )
        }

    override suspend fun getByRelativePaths(relativePaths: List<String>): List<S3SyncMetadataEntity> =
        relativePaths.mapNotNull(entities::get)

    override suspend fun upsertAll(entities: List<S3SyncMetadataEntity>) = Unit

    override suspend fun deleteByRelativePath(relativePath: String) = Unit

    override suspend fun deleteByRelativePaths(relativePaths: List<String>) = Unit

    override suspend fun clearAll() = Unit

    override suspend fun replaceAll(entities: List<S3SyncMetadataEntity>) = Unit
}

private fun incrementalStatusConfig() =
    S3ResolvedConfig(
        endpointUrl = "https://s3.example.com",
        region = "us-east-1",
        bucket = "bucket",
        prefix = "",
        accessKeyId = "access",
        secretAccessKey = "secret",
        sessionToken = null,
        pathStyle = com.lomo.domain.model.S3PathStyle.PATH_STYLE,
        encryptionMode = com.lomo.domain.model.S3EncryptionMode.NONE,
        encryptionPassword = null,
    )

private class FailingStatusFullScanMetadataDao(
    private val initial: List<S3SyncMetadataEntity> = emptyList(),
    private val errorMessage: String,
) : S3SyncMetadataDao {
    private val entities = linkedMapOf<String, S3SyncMetadataEntity>()

    init {
        initial.forEach { entity ->
            entities[entity.relativePath] = entity
        }
    }

    override suspend fun getAll(): List<S3SyncMetadataEntity> = error(errorMessage)

    override suspend fun getAllRemoteMetadataSnapshots(): List<S3SyncRemoteMetadataSnapshot> =
        entities.values.map { entity ->
            S3SyncRemoteMetadataSnapshot(
                relativePath = entity.relativePath,
                remotePath = entity.remotePath,
                etag = entity.etag,
                remoteLastModified = entity.remoteLastModified,
            )
        }

    override suspend fun getByRelativePaths(relativePaths: List<String>): List<S3SyncMetadataEntity> =
        relativePaths.mapNotNull(entities::get)

    override suspend fun upsertAll(entities: List<S3SyncMetadataEntity>) = Unit

    override suspend fun deleteByRelativePath(relativePath: String) = Unit

    override suspend fun deleteByRelativePaths(relativePaths: List<String>) = Unit

    override suspend fun clearAll() = Unit

    override suspend fun replaceAll(entities: List<S3SyncMetadataEntity>) = Unit
}
