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
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/*
 * Test Contract:
 * - Unit under test: S3SyncExecutor
 * - Behavior focus: after bootstrap, S3 sync should use manifest revision probing plus local journal fast paths instead of repeating full local and remote discovery, including SAF vault-root mode.
 * - Observable outcomes: returned S3SyncResult, local discovery call counts, manifest body download count, remote list call counts, uploaded object keys, and journal drain behavior.
 * - Red phase: Fails before the fix because S3 sync always downloads the manifest body and still performs full discovery for vault-root paths even when revision metadata and local journal are sufficient.
 * - Excludes: AWS SDK transport internals, Room generated code, WorkManager scheduling, and UI rendering.
 */
class S3SyncIncrementalExecutorTest {
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
        every { credentialStore.getAccessKeyId() } returns "access"
        every { credentialStore.getSecretAccessKey() } returns "secret"
        every { credentialStore.getSessionToken() } returns null
        every { credentialStore.getEncryptionPassword() } returns null
        every { credentialStore.getEncryptionPassword2() } returns null

        coEvery { dataStore.updateS3LastSyncTime(any()) } returns Unit
        coEvery { memoSynchronizer.refresh(any()) } returns Unit

        protocolStateStore = InMemoryS3SyncProtocolStateStore()
        journalStore = InMemoryS3LocalChangeJournalStore()
        manifestStore = DefaultS3RemoteManifestStore(S3SyncEncodingSupport())
    }

    @Test
    fun `performSync skips full discovery when manifest revision is unchanged and journal is empty`() =
        runTest {
            val managedPath = "lomo/memo/note.md"
            val metadataDao =
                IncrementalRecordingMetadataDao(
                    initial = listOf(stableMetadata(managedPath, etag = "etag-1", lastModified = 10L)),
                )
            val manifest =
                S3RemoteManifest(
                    revision = 7L,
                    generatedAt = 70L,
                    entries =
                        listOf(
                            S3RemoteManifestEntry(
                                relativePath = managedPath,
                                remotePath = managedPath,
                                etag = "etag-1",
                                remoteLastModified = 10L,
                            ),
                        ),
                )
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastManifestRevision = 7L,
                    indexedLocalFileCount = 1,
                    indexedRemoteFileCount = 1,
                ),
            )
            val client = IncrementalManifestClient(manifestStore = manifestStore, manifest = manifest)
            val executor = createExecutor(client = client, metadataDao = metadataDao)

            val result = executor.performSync()

            val success = result as S3SyncResult.Success
            assertEquals("S3 already up to date", success.message)
            assertTrue(success.outcomes.isEmpty())
            coVerify(exactly = 0) { markdownStorageDataSource.listMetadataIn(MemoDirectoryType.MAIN) }
            coVerify(exactly = 0) { localMediaSyncStore.listFiles(any()) }
            assertEquals(0, client.listCallCount)
            assertEquals(0, client.manifestGetCount)
        }

    @Test
    fun `performSync uploads only the journaled memo without full discovery`() =
        runTest {
            val managedPath = "lomo/memo/note.md"
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastManifestRevision = 11L,
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
                    updatedAt = 120L,
                ),
            )
            coEvery { markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "note.md") } returns "# note"
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(
                    MemoDirectoryType.MAIN,
                    "note.md",
                )
            } returns FileMetadata(filename = "note.md", lastModified = 30L)
            val client =
                IncrementalManifestClient(
                    manifestStore = manifestStore,
                    manifest = S3RemoteManifest(revision = 11L, generatedAt = 110L, entries = emptyList()),
                )
            val executor = createExecutor(client = client, metadataDao = IncrementalRecordingMetadataDao())

            val result = executor.performSync()

            val success = result as S3SyncResult.Success
            assertEquals("S3 sync completed", success.message)
            assertEquals(
                listOf(S3SyncDirection.UPLOAD to S3SyncReason.LOCAL_ONLY),
                success.outcomes.map { it.direction to it.reason },
            )
            coVerify(exactly = 0) { markdownStorageDataSource.listMetadataIn(MemoDirectoryType.MAIN) }
            coVerify(exactly = 0) { localMediaSyncStore.listFiles(any()) }
            assertEquals(0, client.listCallCount)
            assertTrue(client.putKeys.contains(managedPath))
            assertTrue(client.putKeys.contains(manifestStore.manifestKey(resolvedConfig())))
            assertTrue(journalStore.read().isEmpty())
        }

    @Test
    fun `performSync does not full-scan metadata for local-only incremental sync`() =
        runTest {
            val managedPath = "lomo/memo/note.md"
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastManifestRevision = 12L,
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
                    updatedAt = 120L,
                ),
            )
            coEvery { markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "note.md") } returns "# note"
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(
                    MemoDirectoryType.MAIN,
                    "note.md",
                )
            } returns FileMetadata(filename = "note.md", lastModified = 30L)
            val client =
                IncrementalManifestClient(
                    manifestStore = manifestStore,
                    manifest = S3RemoteManifest(revision = 12L, generatedAt = 120L, entries = emptyList()),
                )
            val executor =
                createExecutor(
                    client = client,
                    metadataDao = FailingFullScanMetadataDao(errorMessage = "full metadata scan should be skipped"),
                )

            val result = executor.performSync()

            val success = result as S3SyncResult.Success
            assertEquals("S3 sync completed", success.message)
            assertTrue(client.putKeys.contains(managedPath))
        }

    @Test
    fun `performSync downloads remote manifest delta without full discovery`() =
        runTest {
            val managedPath = "lomo/memo/note.md"
            val metadataDao =
                IncrementalRecordingMetadataDao(
                    initial =
                        listOf(
                            stableMetadata(
                                relativePath = managedPath,
                                etag = "etag-old",
                                lastModified = 30L,
                            ),
                        ),
                )
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastManifestRevision = 3L,
                    indexedLocalFileCount = 1,
                    indexedRemoteFileCount = 1,
                ),
            )
            val manifest =
                S3RemoteManifest(
                    revision = 4L,
                    generatedAt = 140L,
                    entries =
                        listOf(
                            S3RemoteManifestEntry(
                                relativePath = managedPath,
                                remotePath = managedPath,
                                etag = "etag-new",
                                remoteLastModified = 40L,
                            ),
                        ),
                )
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(
                    MemoDirectoryType.MAIN,
                    "note.md",
                )
            } returnsMany
                listOf(
                    FileMetadata(filename = "note.md", lastModified = 30L),
                    FileMetadata(filename = "note.md", lastModified = 40L),
                )
            coEvery {
                markdownStorageDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = "note.md",
                    content = "# updated",
                    append = false,
                    uri = null,
                )
            } returns null
            val client =
                IncrementalManifestClient(
                    manifestStore = manifestStore,
                    manifest = manifest,
                    payloads =
                        mapOf(
                            managedPath to
                                S3RemoteObjectPayload(
                                    key = managedPath,
                                    eTag = "etag-new",
                                    lastModified = 40L,
                                    metadata = emptyMap(),
                                    bytes = "# updated".toByteArray(),
                                ),
                        ),
                )
            val executor = createExecutor(client = client, metadataDao = metadataDao)

            val result = executor.performSync()

            val success = result as S3SyncResult.Success
            assertEquals("S3 sync completed", success.message)
            assertEquals(
                listOf(S3SyncDirection.DOWNLOAD to S3SyncReason.REMOTE_ONLY),
                success.outcomes.map { it.direction to it.reason },
            )
            coVerify(exactly = 0) { markdownStorageDataSource.listMetadataIn(MemoDirectoryType.MAIN) }
            coVerify(exactly = 0) { localMediaSyncStore.listFiles(any()) }
            assertEquals(0, client.listCallCount)
            assertTrue(client.objectGetKeys.contains(managedPath))
        }

    @Test
    fun `performSync applies remote manifest delta without full metadata entity scan`() =
        runTest {
            val managedPath = "lomo/memo/note.md"
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastManifestRevision = 16L,
                    indexedLocalFileCount = 1,
                    indexedRemoteFileCount = 1,
                ),
            )
            val manifest =
                S3RemoteManifest(
                    revision = 17L,
                    generatedAt = 170L,
                    entries =
                        listOf(
                            S3RemoteManifestEntry(
                                relativePath = managedPath,
                                remotePath = managedPath,
                                etag = "etag-new",
                                remoteLastModified = 40L,
                            ),
                        ),
                )
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(
                    MemoDirectoryType.MAIN,
                    "note.md",
                )
            } returnsMany
                listOf(
                    FileMetadata(filename = "note.md", lastModified = 30L),
                    FileMetadata(filename = "note.md", lastModified = 40L),
                )
            coEvery {
                markdownStorageDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = "note.md",
                    content = "# updated",
                    append = false,
                    uri = null,
                )
            } returns null
            val client =
                IncrementalManifestClient(
                    manifestStore = manifestStore,
                    manifest = manifest,
                    payloads =
                        mapOf(
                            managedPath to
                                S3RemoteObjectPayload(
                                    key = managedPath,
                                    eTag = "etag-new",
                                    lastModified = 40L,
                                    metadata = emptyMap(),
                                    bytes = "# updated".toByteArray(),
                                ),
                        ),
                )
            val executor =
                createExecutor(
                    client = client,
                    metadataDao =
                        FailingFullScanMetadataDao(
                            initial =
                                listOf(
                                    stableMetadata(
                                        relativePath = managedPath,
                                        etag = "etag-old",
                                        lastModified = 30L,
                                    ),
                                ),
                            errorMessage = "full metadata entity scan should be skipped",
                        ),
                )

            val result = executor.performSync()

            val success = result as S3SyncResult.Success
            assertEquals("S3 sync completed", success.message)
            assertEquals(
                listOf(S3SyncDirection.DOWNLOAD to S3SyncReason.REMOTE_ONLY),
                success.outcomes.map { it.direction to it.reason },
            )
            assertEquals(0, client.listCallCount)
            assertTrue(client.objectGetKeys.contains(managedPath))
        }

    @Test
    fun `performSync falls back to bootstrap when manifest is unavailable`() =
        runTest {
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastManifestRevision = 1L,
                    indexedLocalFileCount = 0,
                    indexedRemoteFileCount = 0,
                ),
            )
            coEvery { markdownStorageDataSource.listMetadataIn(MemoDirectoryType.MAIN) } returns
                listOf(FileMetadata(filename = "note.md", lastModified = 10L))
            coEvery { markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "note.md") } returns "# note"
            val client =
                IncrementalManifestClient(
                    manifestStore = manifestStore,
                    manifest = null,
                    remoteObjects = emptyList(),
                    throwOnManifestRead = true,
                )
            val executor = createExecutor(client = client, metadataDao = IncrementalRecordingMetadataDao())

            val result = executor.performSync()

            val success = result as S3SyncResult.Success
            assertEquals("S3 sync completed", success.message)
            coVerify(exactly = 1) { markdownStorageDataSource.listMetadataIn(MemoDirectoryType.MAIN) }
            assertEquals(1, client.listCallCount)
        }

    @Test
    fun `performSync uses manifest metadata revision for local-only incremental sync without downloading manifest`() =
        runTest {
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastManifestRevision = 12L,
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
                    updatedAt = 120L,
                ),
            )
            coEvery { markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "note.md") } returns "# note"
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(
                    MemoDirectoryType.MAIN,
                    "note.md",
                )
            } returns FileMetadata(filename = "note.md", lastModified = 30L)
            val client =
                IncrementalManifestClient(
                    manifestStore = manifestStore,
                    manifest =
                        S3RemoteManifest(
                            revision = 12L,
                            generatedAt = 120L,
                            entries = emptyList(),
                        ),
                    throwOnManifestRead = true,
                    manifestRevisionOverride = 12L,
                )

            val result = createExecutor(client = client, metadataDao = IncrementalRecordingMetadataDao()).performSync()

            val success = result as S3SyncResult.Success
            assertEquals("S3 sync completed", success.message)
            assertTrue(client.putKeys.contains("lomo/memo/note.md"))
            assertEquals(0, client.manifestGetCount)
            assertEquals(0, client.listCallCount)
        }

    @Test
    fun `performSync skips saf vault root full discovery when revision metadata is unchanged`() =
        runTest {
            every { dataStore.s3LocalSyncDirectory } returns flowOf("content://tree/primary%3AVault")
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastManifestRevision = 13L,
                    indexedLocalFileCount = 2,
                    indexedRemoteFileCount = 2,
                    lastSuccessfulSyncAt = System.currentTimeMillis(),
                ),
            )
            val client =
                IncrementalManifestClient(
                    manifestStore = manifestStore,
                    manifest =
                        S3RemoteManifest(
                            revision = 13L,
                            generatedAt = 130L,
                            entries = emptyList(),
                        ),
                    throwOnManifestRead = true,
                    manifestRevisionOverride = 13L,
                )

            val result =
                createExecutor(
                    client = client,
                    metadataDao = IncrementalRecordingMetadataDao(),
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
                ).performSync()

            val success = result as S3SyncResult.Success
            assertEquals("S3 already up to date", success.message)
            assertEquals(0, client.manifestGetCount)
            assertEquals(0, client.listCallCount)
        }

    @Test
    fun `performSync reuses vault root journal path without redundant upload`() =
        runTest {
            val vaultRoot = Files.createTempDirectory("s3-sync-vault-root").toFile()
            val localFile = vaultRoot.resolve("note.md")
            localFile.writeText("# note")
            assertTrue(localFile.setLastModified(30L))
            every { dataStore.s3LocalSyncDirectory } returns flowOf(vaultRoot.absolutePath)
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastManifestRevision = 14L,
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
                    updatedAt = 140L,
                ),
            )
            val client =
                IncrementalManifestClient(
                    manifestStore = manifestStore,
                    manifest =
                        S3RemoteManifest(
                            revision = 14L,
                            generatedAt = 140L,
                            entries =
                                listOf(
                                    S3RemoteManifestEntry(
                                        relativePath = "note.md",
                                        remotePath = "note.md",
                                        etag = "etag-note",
                                        remoteLastModified = 30L,
                                    ),
                                ),
                        ),
                    throwOnManifestRead = true,
                    manifestRevisionOverride = 14L,
                )

            val result =
                createExecutor(
                    client = client,
                    metadataDao =
                        FailingFullScanMetadataDao(
                            initial =
                                listOf(
                                    stableMetadata(
                                        relativePath = "note.md",
                                        etag = "etag-note",
                                        lastModified = 30L,
                                    ),
                                ),
                            errorMessage = "full metadata scan should be skipped",
                        ),
                ).performSync()

            val success = result as S3SyncResult.Success
            assertEquals("S3 already up to date", success.message)
            assertTrue(client.putKeys.isEmpty())
            assertEquals(0, client.manifestGetCount)
            assertEquals(0, client.listCallCount)
        }

    @Test
    fun `performSync reuses configured memo subdirectory path under explicit vault root without redundant upload`() =
        runTest {
            val vaultRoot = Files.createTempDirectory("s3-sync-vault-journal").toFile()
            val memoRoot = vaultRoot.resolve("journal").also { it.mkdirs() }
            val imageRoot = vaultRoot.resolve("asset").also { it.mkdirs() }
            val voiceRoot = vaultRoot.resolve("voice").also { it.mkdirs() }
            val localFile = memoRoot.resolve("note.md")
            localFile.writeText("# note")
            assertTrue(localFile.setLastModified(30L))
            every { dataStore.s3LocalSyncDirectory } returns flowOf(vaultRoot.absolutePath)
            every { dataStore.rootDirectory } returns flowOf(memoRoot.absolutePath)
            every { dataStore.imageDirectory } returns flowOf(imageRoot.absolutePath)
            every { dataStore.voiceDirectory } returns flowOf(voiceRoot.absolutePath)
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastManifestRevision = 16L,
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
                    updatedAt = 160L,
                ),
            )
            val client =
                IncrementalManifestClient(
                    manifestStore = manifestStore,
                    manifest =
                        S3RemoteManifest(
                            revision = 16L,
                            generatedAt = 160L,
                            entries =
                                listOf(
                                    S3RemoteManifestEntry(
                                        relativePath = "journal/note.md",
                                        remotePath = "journal/note.md",
                                        etag = "etag-note",
                                        remoteLastModified = 30L,
                                    ),
                                ),
                        ),
                    throwOnManifestRead = true,
                    manifestRevisionOverride = 16L,
                )

            val result =
                createExecutor(
                    client = client,
                    metadataDao =
                        FailingFullScanMetadataDao(
                            initial =
                                listOf(
                                    stableMetadata(
                                        relativePath = "journal/note.md",
                                        etag = "etag-note",
                                        lastModified = 30L,
                                    ),
                                ),
                            errorMessage = "full metadata scan should be skipped",
                        ),
                ).performSync()

            val success = result as S3SyncResult.Success
            assertEquals("S3 already up to date", success.message)
            assertTrue(client.putKeys.isEmpty())
            assertEquals(0, client.manifestGetCount)
            assertEquals(0, client.listCallCount)
        }

    @Test
    fun `performSync uploads generic markdown under explicit vault root without waiting for audit`() =
        runTest {
            val vaultRoot = Files.createTempDirectory("s3-sync-vault-generic").toFile()
            val memoRoot = vaultRoot.resolve("journal").also { it.mkdirs() }
            val imageRoot = vaultRoot.resolve("asset").also { it.mkdirs() }
            val voiceRoot = vaultRoot.resolve("voice").also { it.mkdirs() }
            val boardFile = vaultRoot.resolve("pages.kanban/board.md").apply {
                parentFile?.mkdirs()
                writeText("# board")
            }
            assertTrue(boardFile.setLastModified(40L))
            every { dataStore.s3LocalSyncDirectory } returns flowOf(vaultRoot.absolutePath)
            every { dataStore.rootDirectory } returns flowOf(memoRoot.absolutePath)
            every { dataStore.imageDirectory } returns flowOf(imageRoot.absolutePath)
            every { dataStore.voiceDirectory } returns flowOf(voiceRoot.absolutePath)
            protocolStateStore.write(
                S3SyncProtocolState(
                    lastManifestRevision = 18L,
                    indexedLocalFileCount = 0,
                    indexedRemoteFileCount = 0,
                    lastSuccessfulSyncAt = System.currentTimeMillis(),
                ),
            )
            val client =
                IncrementalManifestClient(
                    manifestStore = manifestStore,
                    manifest = S3RemoteManifest(revision = 18L, generatedAt = 180L, entries = emptyList()),
                    throwOnManifestRead = true,
                    manifestRevisionOverride = 18L,
                )

            val result =
                createExecutor(
                    client = client,
                    metadataDao =
                        FailingFullScanMetadataDao(
                            initial = emptyList(),
                            errorMessage = "full metadata scan should be skipped",
                        ),
                ).performSync()

            val success = result as S3SyncResult.Success
            assertEquals("S3 sync completed", success.message)
            assertEquals(
                listOf(S3SyncDirection.UPLOAD to S3SyncReason.LOCAL_ONLY),
                success.outcomes.map { it.direction to it.reason },
            )
            assertTrue(client.putKeys.contains("pages.kanban/board.md"))
            assertEquals(0, client.manifestGetCount)
            assertEquals(0, client.listCallCount)
        }

    private fun createExecutor(
        client: LomoS3Client,
        metadataDao: S3SyncMetadataDao,
        safTreeAccess: S3SafTreeAccess = UnsupportedS3SafTreeAccess,
    ): S3SyncExecutor {
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
        val encodingSupport = S3SyncEncodingSupport()
        val fileBridge = S3SyncFileBridge(runtime, encodingSupport, safTreeAccess)
        return S3SyncExecutor(
            runtime = runtime,
            support = S3SyncRepositorySupport(runtime),
            encodingSupport = encodingSupport,
            fileBridge = fileBridge,
            actionApplier = S3SyncActionApplier(runtime, encodingSupport, fileBridge),
            protocolStateStore = protocolStateStore,
            localChangeJournalStore = journalStore,
            remoteManifestStore = manifestStore,
        )
    }

    private fun resolvedConfig() =
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
}

private class IncrementalManifestClient(
    private val manifestStore: S3RemoteManifestStore,
    manifest: S3RemoteManifest?,
    private val remoteObjects: List<S3RemoteObject> = emptyList(),
    private val payloads: Map<String, S3RemoteObjectPayload> = emptyMap(),
    private val throwOnManifestRead: Boolean = false,
    private val manifestRevisionOverride: Long? = null,
) : LomoS3Client {
    private val json = Json
    private var currentManifest = manifest

    var listCallCount: Int = 0
        private set
    var manifestGetCount: Int = 0
        private set
    val putKeys = mutableListOf<String>()
    val objectGetKeys = mutableListOf<String>()

    override suspend fun verifyAccess(prefix: String) = Unit

    override suspend fun getObjectMetadata(key: String): S3RemoteObject? {
        val manifestKey = manifestStore.manifestKey(testConfig())
        if (key != manifestKey) {
            return null
        }
        val manifestRevision = manifestRevisionOverride ?: currentManifest?.revision ?: return null
        return S3RemoteObject(
            key = key,
            eTag = "manifest-$manifestRevision",
            lastModified = currentManifest?.generatedAt,
            metadata =
                mapOf(
                    S3_MANIFEST_REVISION_METADATA_KEY to manifestRevision.toString(),
                    S3_MANIFEST_GENERATED_AT_METADATA_KEY to (currentManifest?.generatedAt ?: 0L).toString(),
                ),
        )
    }

    override suspend fun list(
        prefix: String,
        maxKeys: Int?,
    ): List<S3RemoteObject> {
        listCallCount += 1
        return remoteObjects
    }

    override suspend fun getObject(key: String): S3RemoteObjectPayload {
        val manifestKey = manifestStore.manifestKey(testConfig())
        if (key == manifestKey) {
            manifestGetCount += 1
            if (throwOnManifestRead) {
                error("missing manifest")
            }
            val manifest = requireNotNull(currentManifest) { "missing manifest" }
            return S3RemoteObjectPayload(
                key = key,
                eTag = "manifest-${manifest.revision}",
                lastModified = manifest.generatedAt,
                metadata = emptyMap(),
                bytes = json.encodeToString(S3RemoteManifest.serializer(), manifest).toByteArray(),
            )
        }
        objectGetKeys += key
        return requireNotNull(payloads[key]) { "Missing payload for $key" }
    }

    override suspend fun putObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
    ): S3PutObjectResult {
        putKeys += key
        val manifestKey = manifestStore.manifestKey(testConfig())
        if (key == manifestKey) {
            currentManifest =
                json.decodeFromString(
                    S3RemoteManifest.serializer(),
                    String(bytes, Charsets.UTF_8),
                )
            return S3PutObjectResult(eTag = "manifest-write")
        }
        return S3PutObjectResult(eTag = "etag-$key")
    }

    override suspend fun deleteObject(key: String) = Unit

    override fun close() = Unit
}

private fun stableMetadata(
    relativePath: String,
    etag: String,
    lastModified: Long,
) = S3SyncMetadataEntity(
    relativePath = relativePath,
    remotePath = relativePath,
    etag = etag,
    remoteLastModified = lastModified,
    localLastModified = lastModified,
    lastSyncedAt = lastModified,
    lastResolvedDirection = "NONE",
    lastResolvedReason = "UNCHANGED",
)

private class IncrementalRecordingMetadataDao(
    initial: List<S3SyncMetadataEntity> = emptyList(),
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

    override suspend fun upsertAll(entities: List<S3SyncMetadataEntity>) {
        entities.forEach { entity ->
            this.entities[entity.relativePath] = entity
        }
    }

    override suspend fun deleteByRelativePath(relativePath: String) {
        entities.remove(relativePath)
    }

    override suspend fun deleteByRelativePaths(relativePaths: List<String>) {
        relativePaths.forEach(entities::remove)
    }

    override suspend fun clearAll() {
        entities.clear()
    }

    override suspend fun replaceAll(entities: List<S3SyncMetadataEntity>) {
        clearAll()
        upsertAll(entities)
    }
}

private fun testConfig() =
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

private class FailingFullScanMetadataDao(
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

    override suspend fun upsertAll(entities: List<S3SyncMetadataEntity>) {
        entities.forEach { entity ->
            this.entities[entity.relativePath] = entity
        }
    }

    override suspend fun deleteByRelativePath(relativePath: String) {
        entities.remove(relativePath)
    }

    override suspend fun deleteByRelativePaths(relativePaths: List<String>) {
        relativePaths.forEach(entities::remove)
    }

    override suspend fun clearAll() {
        entities.clear()
    }

    override suspend fun replaceAll(entities: List<S3SyncMetadataEntity>) {
        clearAll()
        upsertAll(entities)
    }
}
