package com.lomo.data.repository

import com.lomo.data.local.dao.WebDavSyncMetadataDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.data.webdav.WebDavClient
import com.lomo.data.webdav.WebDavClientFactory
import com.lomo.data.webdav.WebDavCredentialStore
import com.lomo.data.webdav.WebDavEndpointResolver
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.WebDavProvider
import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.model.WebDavSyncState
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets

/*
 * Test Contract:
 * - Unit under test: WebDavConflictResolver
 * - Behavior focus: per-file conflict choice routing, not-configured short-circuiting, and error/result mapping after legacy capture cleanup.
 * - Observable outcomes: WebDavSyncResult type, state transitions, and local/remote side-effect targets.
 * - Red phase: Fails before the fix because conflict resolution still depends on retired pre-sync capture work for KEEP_REMOTE memo writes.
 * - Excludes: WebDAV transport internals, planner/metadata persistence internals, and UI rendering.
 */
class WebDavConflictResolverTest {
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
    private lateinit var markdownStorageDataSource: MarkdownStorageDataSource

    @MockK(relaxed = true)
    private lateinit var localMediaSyncStore: LocalMediaSyncStore

    @MockK(relaxed = true)
    private lateinit var metadataDao: WebDavSyncMetadataDao

    @MockK(relaxed = true)
    private lateinit var memoSynchronizer: MemoSynchronizer

    @MockK(relaxed = true)
    private lateinit var fileBridge: WebDavSyncFileBridge

    private lateinit var stateHolder: WebDavSyncStateHolder
    private lateinit var runtime: WebDavSyncRepositoryContext
    private lateinit var support: WebDavSyncRepositorySupport
    private lateinit var resolver: WebDavConflictResolver

    @Before
    fun setUp() {
        MockKAnnotations.init(this)

        every { dataStore.webDavSyncEnabled } returns flowOf(true)
        every { dataStore.webDavProvider } returns flowOf(WebDavProvider.NUTSTORE.name.lowercase())
        every { dataStore.webDavBaseUrl } returns flowOf(null)
        every { dataStore.webDavEndpointUrl } returns flowOf("https://dav.example.com/root/")
        every { dataStore.webDavUsername } returns flowOf("alice")
        every { dataStore.rootDirectory } returns flowOf("/memo")
        every { dataStore.rootUri } returns flowOf(null)
        every { dataStore.imageDirectory } returns flowOf("/images")
        every { dataStore.imageUri } returns flowOf(null)
        every { dataStore.voiceDirectory } returns flowOf("/voice")
        every { dataStore.voiceUri } returns flowOf(null)
        every {
            endpointResolver.resolve(
                WebDavProvider.NUTSTORE,
                null,
                "https://dav.example.com/root/",
                "alice",
            )
        } returns "https://dav.example.com/root/"
        every { credentialStore.getUsername() } returns null
        every { credentialStore.getPassword() } returns "secret"
        every { clientFactory.create("https://dav.example.com/root/", "alice", "secret") } returns client
        coEvery { memoSynchronizer.refresh() } returns Unit

        stateHolder = WebDavSyncStateHolder()
        runtime =
            WebDavSyncRepositoryContext(
                dataStore = dataStore,
                credentialStore = credentialStore,
                endpointResolver = endpointResolver,
                clientFactory = clientFactory,
                markdownStorageDataSource = markdownStorageDataSource,
                localMediaSyncStore = localMediaSyncStore,
                metadataDao = metadataDao,
                memoSynchronizer = memoSynchronizer,
                planner = WebDavSyncPlanner(),
                stateHolder = stateHolder,
            )
        support = WebDavSyncRepositorySupport(runtime)
        resolver = WebDavConflictResolver(runtime, support, fileBridge)
    }

    @Test
    fun `resolveConflicts KEEP_LOCAL uploads local content to remote`() =
        runTest {
            val path = "lomo/memo/2026_03_24.md"
            val file = conflictFile(path = path, local = "LOCAL", remote = "REMOTE")
            every { fileBridge.contentTypeForPath(path, any()) } returns "text/plain"
            val resolution = SyncConflictResolution(perFileChoices = mapOf(path to SyncConflictResolutionChoice.KEEP_LOCAL))

            val result = resolver.resolveConflicts(resolution, conflictSet(file))

            assertEquals(WebDavSyncResult.Success("Conflicts resolved"), result)
            verify(exactly = 1) {
                client.put(
                    path = path,
                    bytes = "LOCAL".toByteArray(StandardCharsets.UTF_8),
                    contentType = "text/plain",
                    lastModifiedHint = null,
                )
            }
            coVerify(exactly = 1) { memoSynchronizer.refresh() }
            assertTrue(stateHolder.state.value is WebDavSyncState.Success)
        }

    @Test
    fun `resolveConflicts KEEP_REMOTE memo writes markdown content to MAIN directory`() =
        runTest {
            val path = "lomo/memo/2026_03_24.md"
            val file = conflictFile(path = path, local = "LOCAL", remote = "REMOTE")
            every { fileBridge.isMemoPath(path, any()) } returns true
            every { fileBridge.extractMemoFilename(path, any()) } returns "2026_03_24.md"
            coEvery {
                markdownStorageDataSource.saveFileIn(
                    directory = com.lomo.data.source.MemoDirectoryType.MAIN,
                    filename = "2026_03_24.md",
                    content = "REMOTE",
                    append = false,
                    uri = null,
                )
            } returns null
            val resolution = SyncConflictResolution(perFileChoices = mapOf(path to SyncConflictResolutionChoice.KEEP_REMOTE))

            val result = resolver.resolveConflicts(resolution, conflictSet(file))

            assertEquals(WebDavSyncResult.Success("Conflicts resolved"), result)
            coVerify(exactly = 1) {
                markdownStorageDataSource.saveFileIn(
                    directory = com.lomo.data.source.MemoDirectoryType.MAIN,
                    filename = "2026_03_24.md",
                    content = "REMOTE",
                    append = false,
                    uri = null,
                )
            }
            coVerify(exactly = 0) { localMediaSyncStore.writeBytes(any(), any(), any()) }
        }

    @Test
    fun `resolveConflicts KEEP_REMOTE media writes bytes into local media store`() =
        runTest {
            val path = "lomo/images/a.png"
            val file = conflictFile(path = path, local = "LOCAL", remote = "REMOTE")
            every { fileBridge.isMemoPath(path, any()) } returns false
            val resolution = SyncConflictResolution(perFileChoices = mapOf(path to SyncConflictResolutionChoice.KEEP_REMOTE))

            val result = resolver.resolveConflicts(resolution, conflictSet(file))

            assertEquals(WebDavSyncResult.Success("Conflicts resolved"), result)
            coVerify(exactly = 1) {
                localMediaSyncStore.writeBytes(
                    relativePath = path,
                    bytes = "REMOTE".toByteArray(StandardCharsets.UTF_8),
                    layout = any<SyncDirectoryLayout>(),
                )
            }
            coVerify(exactly = 0) { markdownStorageDataSource.saveFileIn(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `resolveConflicts returns NotConfigured when config is missing`() =
        runTest {
            every { dataStore.webDavSyncEnabled } returns flowOf(false)
            val file = conflictFile(path = "lomo/memo/2026_03_24.md", local = "L", remote = "R")

            val result = resolver.resolveConflicts(SyncConflictResolution(emptyMap()), conflictSet(file))

            assertEquals(WebDavSyncResult.NotConfigured, result)
            assertEquals(WebDavSyncState.NotConfigured, stateHolder.state.value)
            verify(exactly = 0) { clientFactory.create(any(), any(), any()) }
        }

    @Test
    fun `resolveConflicts keeps Success result when refresh fails`() =
        runTest {
            val path = "lomo/memo/2026_03_24.md"
            val file = conflictFile(path = path, local = "LOCAL", remote = "REMOTE")
            every { fileBridge.contentTypeForPath(path, any()) } returns "text/plain"
            coEvery { memoSynchronizer.refresh() } throws IllegalStateException("refresh failed")

            val result = resolver.resolveConflicts(SyncConflictResolution(emptyMap()), conflictSet(file))

            assertEquals(WebDavSyncResult.Success("Conflicts resolved"), result)
            assertTrue(stateHolder.state.value is WebDavSyncState.Success)
        }

    @Test
    fun `resolveConflicts maps resolver exception to WebDavSyncResult Error`() =
        runTest {
            every { clientFactory.create(any(), any(), any()) } throws IllegalStateException("client init failed")
            val file = conflictFile(path = "lomo/memo/2026_03_24.md", local = "LOCAL", remote = "REMOTE")

            val result = resolver.resolveConflicts(SyncConflictResolution(emptyMap()), conflictSet(file))

            assertTrue(result is WebDavSyncResult.Error)
            result as WebDavSyncResult.Error
            assertTrue(result.message.contains("client init failed"))
            assertTrue(result.exception is IllegalStateException)
            assertTrue(stateHolder.state.value is WebDavSyncState.Error)
        }

    private fun conflictSet(file: SyncConflictFile): SyncConflictSet =
        SyncConflictSet(
            source = SyncBackendType.WEBDAV,
            files = listOf(file),
            timestamp = 1L,
        )

    private fun conflictFile(
        path: String,
        local: String?,
        remote: String?,
    ): SyncConflictFile =
        SyncConflictFile(
            relativePath = path,
            localContent = local,
            remoteContent = remote,
            isBinary = false,
        )
}
