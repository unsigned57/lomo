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


import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.local.entity.WebDavSyncMetadataEntity
import com.lomo.data.source.FileMetadata
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.testing.fakes.FakeFileDataSource
import com.lomo.data.testing.fakes.FakeWebDavSyncMetadataDao
import com.lomo.data.webdav.Dav4jvmWebDavClientFactory
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
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue

/*
 * Behavior Contract:
 * - Unit under test: WebDavSyncExecutor via WebDavSyncRepositoryImpl
 * - Behavior focus: external-to-local WebDAV sync ordering and metadata/refresh follow-up after legacy capture cleanup.
 * - Observable outcomes: returned WebDavSyncResult, local writes, metadata persistence, and sync side effects.
 * - TDD proof: Fails before the fix because WebDAV sync still depends on retired pre-sync capture work in the download path.
 * - Excludes: WebDAV transport internals, remote listing algorithms, and UI state presentation.
 */
class WebDavSyncRepositoryImplTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("test connection success keeps sync state idle") { `test connection success keeps sync state idle`() }

        test("test connection failure does not overwrite sync state") { `test connection failure does not overwrite sync state`() }

        test("sync with upload only relists remote files only") { `sync with upload only relists remote files only`() }

        test("sync with download only relists local files only") { `sync with download only relists local files only`() }

        test("sync with no changes and existing metadata reuses initial listings") { `sync with no changes and existing metadata reuses initial listings`() }

        test("sync revalidates remote deletion before deleting unchanged local memo") { `sync revalidates remote deletion before deleting unchanged local memo`() }
    }


    @MockK(relaxed = true)
    private lateinit var dataStore: LomoDataStore

    @MockK(relaxed = true)
    private lateinit var credentialStore: WebDavCredentialStore

    @MockK(relaxed = true)
    private lateinit var configurationClientFactory: Dav4jvmWebDavClientFactory

    @MockK(relaxed = true)
    private lateinit var endpointResolver: WebDavEndpointResolver

    @MockK(relaxed = true)
    private lateinit var clientFactory: WebDavClientFactory

    @MockK(relaxed = true)
    private lateinit var client: WebDavClient

    private val fileDataSource = FakeFileDataSource()

    @MockK(relaxed = true)
    private lateinit var localMediaSyncStore: LocalMediaSyncStore

    private val metadataDao = FakeWebDavSyncMetadataDao()

    @MockK(relaxed = true)
    private lateinit var memoSynchronizer: MemoSynchronizer

    private lateinit var planner: WebDavSyncPlanner
    private lateinit var repository: WebDavSyncRepositoryImpl

    private fun setUp() {
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
                        clientFactory = configurationClientFactory,
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

    private fun `test connection success keeps sync state idle`() =
        runTest {
            val result = repository.testConnection()

            (result is WebDavSyncResult.Success).shouldBeTrue()
            repository.syncState().first() shouldBe WebDavSyncState.Idle
            verify(exactly = 1) { client.testConnection() }
        }

    private fun `test connection failure does not overwrite sync state`() =
        runTest {
            val failure = IllegalStateException("boom")
            every { client.testConnection() } throws failure

            val result = repository.testConnection()

            (result is WebDavSyncResult.Error).shouldBeTrue()
            repository.syncState().first() shouldBe WebDavSyncState.Idle
        }

    private fun `sync with upload only relists remote files only`() =
        runTest {
            fileDataSource.listMetadataInResult = {
                listOf(FileMetadata("note.md", 100L))
            }
            fileDataSource.saveFileIn(MemoDirectoryType.MAIN, "note.md", "# note", false)
            coEvery { localMediaSyncStore.listFiles(any()) } returns emptyMap()
            every { client.list("lomo/memos") } returnsMany
                listOf(emptyList(), listOf(remoteMemo("lomo/memos/note.md", "etag-2", 100L)))
            every { client.list("lomo/images") } returnsMany listOf(emptyList(), emptyList())
            every { client.list("lomo/voice") } returnsMany listOf(emptyList(), emptyList())

            val result = repository.sync()

            (result is WebDavSyncResult.Success).shouldBeTrue()
            fileDataSource.listMetadataInCalls.size shouldBe 1
            coVerify(exactly = 1) { localMediaSyncStore.listFiles(any()) }
            verify(exactly = 2) { client.list("lomo/memos") }
            verify(exactly = 1) { client.list("lomo/images") }
            verify(exactly = 1) { client.list("lomo/voice") }
            verify(exactly = 1) {
                client.put("lomo/memos/note.md", any(), any(), 100L, null, true)
            }
            metadataDao.allEntities.map { it.relativePath } shouldBe listOf("lomo/memos/note.md")
            metadataDao.allEntities.single().etag shouldBe "etag-2"
        }

    private fun `sync with download only relists local files only`() =
        runTest {
            var listCallCount = 0
            fileDataSource.listMetadataInResult = {
                listCallCount++
                if (listCallCount == 1) {
                    emptyList()
                } else {
                    listOf(FileMetadata("note.md", 100L))
                }
            }
            coEvery { localMediaSyncStore.listFiles(any()) } returns emptyMap()
            every { client.list("lomo/memos") } returns listOf(remoteMemo("lomo/memos/note.md", "etag-1", 100L))
            every { client.list("lomo/images") } returns emptyList()
            every { client.list("lomo/voice") } returns emptyList()
            every {
                client.get("lomo/memos/note.md")
            } returns WebDavRemoteFile("lomo/memos/note.md", "# note".toByteArray(), "etag-1", 100L)

            val result = repository.sync()

            (result is WebDavSyncResult.Success).shouldBeTrue()
            fileDataSource.listMetadataInCalls.size shouldBe 2
            coVerify(exactly = 2) { localMediaSyncStore.listFiles(any()) }
            verify(exactly = 1) { client.list("lomo/memos") }
            verify(exactly = 1) { client.list("lomo/images") }
            verify(exactly = 1) { client.list("lomo/voice") }
            verify(exactly = 1) { client.get("lomo/memos/note.md") }
            metadataDao.allEntities.map { it.relativePath } shouldBe listOf("lomo/memos/note.md")
            metadataDao.allEntities.single().localLastModified shouldBe 100L
        }

    private fun `sync with no changes and existing metadata reuses initial listings`() =
        runTest {
            fileDataSource.listMetadataInResult = {
                listOf(FileMetadata("note.md", 100L))
            }
            fileDataSource.saveFileIn(MemoDirectoryType.MAIN, "note.md", "# note", false)
            coEvery { localMediaSyncStore.listFiles(any()) } returns emptyMap()
            metadataDao.allEntities.add(
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

            val result = repository.sync()

            (result is WebDavSyncResult.Success).shouldBeTrue()
            fileDataSource.listMetadataInCalls.size shouldBe 1
            coVerify(exactly = 1) { localMediaSyncStore.listFiles(any()) }
            verify(exactly = 1) { client.list("lomo/memos") }
            verify(exactly = 1) { client.list("lomo/images") }
            verify(exactly = 1) { client.list("lomo/voice") }
            verify(exactly = 0) { client.put(any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { client.get(any()) }
            verify(exactly = 0) { client.delete(any(), any()) }
            metadataDao.allEntities.map { it.relativePath } shouldBe listOf("lomo/memos/note.md")
            metadataDao.allEntities.single().etag shouldBe "etag-1"
        }

    private fun `sync revalidates remote deletion before deleting unchanged local memo`() =
        runTest {
            fileDataSource.listMetadataInResult = {
                listOf(FileMetadata("note.md", 100L))
            }
            fileDataSource.saveFileIn(MemoDirectoryType.MAIN, "note.md", "# note", false)
            coEvery { localMediaSyncStore.listFiles(any()) } returns emptyMap()
            metadataDao.allEntities.add(
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
            every { client.list("lomo/memos") } returnsMany
                listOf(
                    emptyList(),
                    listOf(remoteMemo("lomo/memos/note.md", "etag-2", 200L)),
                )
            every { client.list("lomo/images") } returns emptyList()
            every { client.list("lomo/voice") } returns emptyList()
            every {
                client.get("lomo/memos/note.md")
            } returns WebDavRemoteFile("lomo/memos/note.md", "# revived".toByteArray(), "etag-2", 200L)

            val result = repository.sync()

            (result is WebDavSyncResult.Success).shouldBeTrue()
            verify(exactly = 2) { client.list("lomo/memos") }
            verify(exactly = 1) { client.get("lomo/memos/note.md") }
            fileDataSource.files[Pair(MemoDirectoryType.MAIN, "note.md")] shouldBe "# revived"
            fileDataSource.deleteFileInCalls.isEmpty() shouldBe true
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
