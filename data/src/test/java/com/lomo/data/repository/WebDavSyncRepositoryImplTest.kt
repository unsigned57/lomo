package com.lomo.data.repository

import com.lomo.data.local.dao.WebDavSyncMetadataDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.local.entity.WebDavSyncMetadataEntity
import com.lomo.data.source.FileDataSource
import com.lomo.data.source.FileMetadata
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.data.webdav.WebDavClient
import com.lomo.data.webdav.WebDavClientFactory
import com.lomo.data.webdav.WebDavCredentialStore
import com.lomo.data.webdav.WebDavEndpointResolver
import com.lomo.data.webdav.WebDavRemoteFile
import com.lomo.data.webdav.WebDavRemoteResource
import com.lomo.domain.model.WebDavProvider
import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.model.WebDavSyncState
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: WebDavSyncExecutor via WebDavSyncRepositoryImpl
 * - Behavior focus: external-to-local WebDAV sync ordering and metadata/refresh follow-up after legacy capture cleanup.
 * - Observable outcomes: returned WebDavSyncResult, local writes, metadata persistence, and sync side effects.
 * - Red phase: Fails before the fix because WebDAV sync still depends on retired pre-sync capture work in the download path.
 * - Excludes: WebDAV transport internals, remote listing algorithms, and UI state presentation.
 */
class WebDavSyncRepositoryImplTest {
    @MockK(relaxed = true)
    private lateinit var dataStore: LomoDataStore

    @MockK(relaxed = true)
    private lateinit var credentialStore: WebDavCredentialStore

    @MockK(relaxed = true)
    private lateinit var endpointResolver: WebDavEndpointResolver

    @MockK(relaxed = true)
    private lateinit var clientFactory: WebDavClientFactory

    @MockK(relaxed = true)
    private lateinit var client: WebDavClient

    @MockK(relaxed = true)
    private lateinit var fileDataSource: FileDataSource

    @MockK(relaxed = true)
    private lateinit var localMediaSyncStore: LocalMediaSyncStore

    @MockK(relaxed = true)
    private lateinit var metadataDao: WebDavSyncMetadataDao

    @MockK(relaxed = true)
    private lateinit var memoSynchronizer: MemoSynchronizer

    private lateinit var planner: WebDavSyncPlanner
    private lateinit var repository: WebDavSyncRepositoryImpl

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        planner = WebDavSyncPlanner()
        every { dataStore.webDavSyncEnabled } returns flowOf(true)
        every { dataStore.webDavProvider } returns flowOf(WebDavProvider.NUTSTORE.name.lowercase())
        every { dataStore.webDavBaseUrl } returns flowOf(null)
        every { dataStore.webDavEndpointUrl } returns flowOf("https://dav.example.com/root/")
        every { dataStore.webDavUsername } returns flowOf("alice")
        every { dataStore.webDavLastSyncTime } returns flowOf(0L)
        every { dataStore.webDavAutoSyncEnabled } returns flowOf(false)
        every { dataStore.webDavAutoSyncInterval } returns flowOf("24h")
        every { dataStore.webDavSyncOnRefresh } returns flowOf(false)
        // Directory settings for SyncDirectoryLayout
        every { dataStore.rootDirectory } returns flowOf("/memos")
        every { dataStore.rootUri } returns flowOf(null)
        every { dataStore.imageDirectory } returns flowOf("/images")
        every { dataStore.imageUri } returns flowOf(null)
        every { dataStore.voiceDirectory } returns flowOf("/voice")
        every { dataStore.voiceUri } returns flowOf(null)
        every { endpointResolver.resolve(WebDavProvider.NUTSTORE, null, "https://dav.example.com/root/", "alice") } returns
            "https://dav.example.com/root/"
        every { credentialStore.getUsername() } returns null
        every { credentialStore.getPassword() } returns "secret"
        every { clientFactory.create("https://dav.example.com/root/", "alice", "secret") } returns client
        val stateHolder = WebDavSyncStateHolder()
        val runtime =
            WebDavSyncRepositoryContext(
                dataStore = dataStore,
                credentialStore = credentialStore,
                endpointResolver = endpointResolver,
                clientFactory = clientFactory,
                markdownStorageDataSource = fileDataSource,
                localMediaSyncStore = localMediaSyncStore,
                metadataDao = metadataDao,
                memoSynchronizer = memoSynchronizer,
                planner = planner,
                stateHolder = stateHolder,
            )
        val support = WebDavSyncRepositorySupport(runtime)
        val fileBridge = WebDavSyncFileBridge(runtime)
        repository =
            WebDavSyncRepositoryImpl(
                configurationRepository = WebDavSyncConfigurationRepositoryImpl(dataStore),
                configurationMutationRepository =
                    WebDavSyncConfigurationMutationRepositoryImpl(
                        dataStore = dataStore,
                        credentialStore = credentialStore,
                    ),
                operationRepository =
                    WebDavSyncOperationRepositoryImpl(
                        syncExecutor =
                            WebDavSyncExecutor(
                                runtime = runtime,
                                support = support,
                                fileBridge = fileBridge,
                                actionApplier = WebDavSyncActionApplier(runtime, fileBridge),
                            ),
                        statusTester =
                            WebDavSyncStatusTester(
                                runtime = runtime,
                                support = support,
                                fileBridge = fileBridge,
                            ),
                        stateHolder = stateHolder,
                    ),
                conflictRepository =
                    WebDavSyncConflictRepositoryImpl(
                        resolver =
                            WebDavConflictResolver(
                                runtime = runtime,
                                support = support,
                                fileBridge = fileBridge,
                            ),
                    ),
                stateRepository = WebDavSyncStateRepositoryImpl(stateHolder),
            )
    }

    @Test
    fun `test connection success keeps sync state idle`() =
        runTest {
            val result = repository.testConnection()

            assertTrue(result is WebDavSyncResult.Success)
            assertEquals(WebDavSyncState.Idle, repository.syncState().first())
            verify(exactly = 1) { client.testConnection() }
        }

    @Test
    fun `test connection failure does not overwrite sync state`() =
        runTest {
            val failure = IllegalStateException("boom")
            every { client.testConnection() } throws failure

            val result = repository.testConnection()

            assertTrue(result is WebDavSyncResult.Error)
            assertEquals(WebDavSyncState.Idle, repository.syncState().first())
        }

    @Test
    fun `sync with upload only relists remote files only`() =
        runTest {
            coEvery { fileDataSource.listMetadataIn(MemoDirectoryType.MAIN) } returns listOf(FileMetadata("note.md", 100L))
            coEvery { fileDataSource.readFileIn(MemoDirectoryType.MAIN, "note.md") } returns "# note"
            coEvery { localMediaSyncStore.listFiles(any()) } returns emptyMap()
            coEvery { metadataDao.getAll() } returns emptyList()
            every { client.list("lomo/memos") } returnsMany
                listOf(emptyList(), listOf(remoteMemo("lomo/memos/note.md", "etag-2", 100L)))
            every { client.list("lomo/images") } returnsMany listOf(emptyList(), emptyList())
            every { client.list("lomo/voice") } returnsMany listOf(emptyList(), emptyList())

            val captured = slot<List<WebDavSyncMetadataEntity>>()

            val result = repository.sync()

            assertTrue(result is WebDavSyncResult.Success)
            coVerify(exactly = 1) { fileDataSource.listMetadataIn(MemoDirectoryType.MAIN) }
            coVerify(exactly = 1) { localMediaSyncStore.listFiles(any()) }
            verify(exactly = 2) { client.list("lomo/memos") }
            verify(exactly = 2) { client.list("lomo/images") }
            verify(exactly = 2) { client.list("lomo/voice") }
            verify(exactly = 1) { client.put("lomo/memos/note.md", any(), any(), 100L) }
            coVerify(exactly = 1) { metadataDao.replaceAll(capture(captured)) }
            assertEquals(listOf("lomo/memos/note.md"), captured.captured.map { it.relativePath })
            assertEquals("etag-2", captured.captured.single().etag)
        }

    @Test
    fun `sync with download only relists local files only`() =
        runTest {
            coEvery {
                fileDataSource.listMetadataIn(MemoDirectoryType.MAIN)
            } returnsMany listOf(emptyList(), listOf(FileMetadata("note.md", 100L)))
            coEvery {
                fileDataSource.saveFileIn(
                    MemoDirectoryType.MAIN,
                    "note.md",
                    "# note",
                    any(),
                    any(),
                )
            } returns null
            coEvery { localMediaSyncStore.listFiles(any()) } returns emptyMap()
            coEvery { metadataDao.getAll() } returns emptyList()
            every { client.list("lomo/memos") } returns listOf(remoteMemo("lomo/memos/note.md", "etag-1", 100L))
            every { client.list("lomo/images") } returns emptyList()
            every { client.list("lomo/voice") } returns emptyList()
            every {
                client.get("lomo/memos/note.md")
            } returns WebDavRemoteFile("lomo/memos/note.md", "# note".toByteArray(), "etag-1", 100L)

            val captured = slot<List<WebDavSyncMetadataEntity>>()

            val result = repository.sync()

            assertTrue(result is WebDavSyncResult.Success)
            coVerify(exactly = 2) { fileDataSource.listMetadataIn(MemoDirectoryType.MAIN) }
            coVerify(exactly = 2) { localMediaSyncStore.listFiles(any()) }
            verify(exactly = 1) { client.list("lomo/memos") }
            verify(exactly = 1) { client.list("lomo/images") }
            verify(exactly = 1) { client.list("lomo/voice") }
            verify(exactly = 1) { client.get("lomo/memos/note.md") }
            coVerify(exactly = 1) { metadataDao.replaceAll(capture(captured)) }
            assertEquals(listOf("lomo/memos/note.md"), captured.captured.map { it.relativePath })
            assertEquals(100L, captured.captured.single().localLastModified)
        }

    @Test
    fun `sync with no changes and existing metadata reuses initial listings`() =
        runTest {
            coEvery { fileDataSource.listMetadataIn(MemoDirectoryType.MAIN) } returns listOf(FileMetadata("note.md", 100L))
            coEvery { localMediaSyncStore.listFiles(any()) } returns emptyMap()
            coEvery { metadataDao.getAll() } returns
                listOf(
                    WebDavSyncMetadataEntity(
                        relativePath = "lomo/memos/note.md",
                        remotePath = "lomo/memos/note.md",
                        etag = "etag-1",
                        remoteLastModified = 100L,
                        localLastModified = 100L,
                        lastSyncedAt = 100L,
                        lastResolvedDirection = WebDavSyncMetadataEntity.NONE,
                        lastResolvedReason = WebDavSyncMetadataEntity.UNCHANGED,
                    ),
                )
            every { client.list("lomo/memos") } returns listOf(remoteMemo("lomo/memos/note.md", "etag-1", 100L))
            every { client.list("lomo/images") } returns emptyList()
            every { client.list("lomo/voice") } returns emptyList()

            val captured = slot<List<WebDavSyncMetadataEntity>>()

            val result = repository.sync()

            assertTrue(result is WebDavSyncResult.Success)
            coVerify(exactly = 1) { fileDataSource.listMetadataIn(MemoDirectoryType.MAIN) }
            coVerify(exactly = 1) { localMediaSyncStore.listFiles(any()) }
            verify(exactly = 1) { client.list("lomo/memos") }
            verify(exactly = 1) { client.list("lomo/images") }
            verify(exactly = 1) { client.list("lomo/voice") }
            verify(exactly = 0) { client.put(any(), any(), any(), any()) }
            verify(exactly = 0) { client.get(any()) }
            verify(exactly = 0) { client.delete(any()) }
            coVerify(exactly = 1) { metadataDao.replaceAll(capture(captured)) }
            assertEquals(listOf("lomo/memos/note.md"), captured.captured.map { it.relativePath })
            assertEquals("etag-1", captured.captured.single().etag)
        }

    private fun remoteMemo(
        path: String,
        etag: String,
        lastModified: Long,
    ): WebDavRemoteResource =
        WebDavRemoteResource(
            path = path,
            isDirectory = false,
            etag = etag,
            lastModified = lastModified,
        )
}
