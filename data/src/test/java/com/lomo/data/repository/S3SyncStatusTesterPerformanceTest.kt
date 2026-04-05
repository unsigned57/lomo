package com.lomo.data.repository

import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.s3.LomoS3Client
import com.lomo.data.s3.LomoS3ClientFactory
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.s3.S3RemoteObject
import com.lomo.data.s3.S3RemoteObjectPayload
import com.lomo.data.source.FileMetadata
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.domain.model.S3SyncStatus
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/*
 * Test Contract:
 * - Unit under test: S3SyncStatusTester
 * - Behavior focus: status queries should overlap local, remote, and metadata scans instead of serializing full discovery work.
 * - Observable outcomes: returned S3SyncStatus and peak concurrent scan count across local, remote, and metadata discovery.
 * - Red phase: Fails before the fix because getStatus performs metadata loading after local and remote discovery instead of overlapping the three preparation steps.
 * - Excludes: AWS transport correctness, planner conflict semantics, metadata persistence mutations, and UI rendering.
 */
class S3SyncStatusTesterPerformanceTest {
    @MockK(relaxed = true)
    private lateinit var dataStore: LomoDataStore

    @MockK(relaxed = true)
    private lateinit var credentialStore: S3CredentialStore

    @MockK(relaxed = true)
    private lateinit var markdownStorageDataSource: MarkdownStorageDataSource

    @MockK(relaxed = true)
    private lateinit var localMediaSyncStore: LocalMediaSyncStore

    @MockK(relaxed = true)
    private lateinit var metadataDao: S3SyncMetadataDao

    @MockK(relaxed = true)
    private lateinit var memoSynchronizer: MemoSynchronizer

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
        coEvery { metadataDao.getAll() } returns emptyList()
        coEvery { localMediaSyncStore.listFiles(any()) } returns emptyMap()
    }

    @Test
    fun `getStatus overlaps local scan and remote listing`() =
        runBlocking {
            val probe = StatusScanProbe()
            val client =
                object : LomoS3Client {
                    override suspend fun verifyAccess(prefix: String) = Unit

                    override suspend fun list(
                        prefix: String,
                        maxKeys: Int?,
                    ): List<S3RemoteObject> {
                        probe.track()
                        return listOf(
                            S3RemoteObject(
                                key = "lomo/memo/note.md",
                                eTag = "etag-note",
                                lastModified = 10L,
                                metadata = emptyMap(),
                            ),
                        )
                    }

                    override suspend fun getObject(key: String): S3RemoteObjectPayload {
                        error("getObject should not be used in getStatus test")
                    }

                    override suspend fun putObject(
                        key: String,
                        bytes: ByteArray,
                        contentType: String,
                        metadata: Map<String, String>,
                    ) = error("putObject should not be used in getStatus test")

                    override suspend fun deleteObject(key: String) = Unit

                    override fun close() = Unit
                }
            coEvery { markdownStorageDataSource.listMetadataIn(MemoDirectoryType.MAIN) } coAnswers {
                probe.track()
                listOf(FileMetadata(filename = "note.md", lastModified = 10L))
            }

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
            val tester =
                S3SyncStatusTester(
                    runtime = runtime,
                    support = S3SyncRepositorySupport(runtime),
                    encodingSupport = S3SyncEncodingSupport(),
                    fileBridge = S3SyncFileBridge(runtime, S3SyncEncodingSupport()),
                )

            val status = tester.getStatus()

            assertEquals(S3SyncStatus(remoteFileCount = 1, localFileCount = 1, pendingChanges = 1, lastSyncTime = null), status)
            assertTrue(
                "Expected status local/remote scans to overlap but saw ${probe.maxConcurrent}",
                probe.maxConcurrent >= 2,
            )
        }

    @Test
    fun `getStatus overlaps metadata load with local and remote discovery`() =
        runBlocking {
            val probe = StatusScanProbe()
            val client =
                object : LomoS3Client {
                    override suspend fun verifyAccess(prefix: String) = Unit

                    override suspend fun list(
                        prefix: String,
                        maxKeys: Int?,
                    ): List<S3RemoteObject> {
                        probe.track()
                        return listOf(
                            S3RemoteObject(
                                key = "lomo/memo/note.md",
                                eTag = "etag-note",
                                lastModified = 10L,
                                metadata = emptyMap(),
                            ),
                        )
                    }

                    override suspend fun getObject(key: String): S3RemoteObjectPayload {
                        error("getObject should not be used in getStatus test")
                    }

                    override suspend fun putObject(
                        key: String,
                        bytes: ByteArray,
                        contentType: String,
                        metadata: Map<String, String>,
                    ) = error("putObject should not be used in getStatus test")

                    override suspend fun deleteObject(key: String) = Unit

                    override fun close() = Unit
                }
            coEvery { markdownStorageDataSource.listMetadataIn(MemoDirectoryType.MAIN) } coAnswers {
                probe.track()
                listOf(FileMetadata(filename = "note.md", lastModified = 10L))
            }
            coEvery { metadataDao.getAll() } coAnswers {
                probe.track()
                emptyList()
            }

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
            val tester =
                S3SyncStatusTester(
                    runtime = runtime,
                    support = S3SyncRepositorySupport(runtime),
                    encodingSupport = S3SyncEncodingSupport(),
                    fileBridge = S3SyncFileBridge(runtime, S3SyncEncodingSupport()),
                )

            val status = tester.getStatus()

            assertEquals(S3SyncStatus(remoteFileCount = 1, localFileCount = 1, pendingChanges = 1, lastSyncTime = null), status)
            assertTrue(
                "Expected status local, remote, and metadata discovery to overlap but saw ${probe.maxConcurrent}",
                probe.maxConcurrent >= 3,
            )
        }
}

private class StatusScanProbe {
    private val inFlight = AtomicInteger(0)
    private val maxConcurrentValue = AtomicInteger(0)

    var maxConcurrent: Int = 0
        private set

    suspend fun track() {
        val concurrent = inFlight.incrementAndGet()
        maxConcurrentValue.updateAndGet { previous -> max(previous, concurrent) }
        maxConcurrent = maxConcurrentValue.get()
        try {
            kotlinx.coroutines.delay(150)
        } finally {
            inFlight.decrementAndGet()
        }
    }
}
