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
import com.lomo.data.webdav.WebDavSmallRemoteFile
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncReviewItem
import com.lomo.domain.model.SyncReviewItemState
import com.lomo.domain.model.SyncReviewResolution
import com.lomo.domain.model.SyncReviewResolutionChoice
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.model.SyncReviewSessionKind
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
import java.nio.charset.StandardCharsets
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue

/*
 * Behavior Contract:
 * - Unit under test: WebDavConflictResolver
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: apply explicit WebDAV conflict-resolution choices while preserving conflicts that cannot be safely resolved.
 *
 * Scenarios:
 * - Given KEEP_LOCAL, when resolution runs, then local content is uploaded.
 * - Given KEEP_REMOTE for a memo or media file, when resolution runs, then remote content is written to the correct local store.
 * - Given MERGE_TEXT can merge memo content, when resolution runs, then the merged memo is written locally and uploaded once.
 * - Given MERGE_TEXT exceeds the text-merge budget, when resolution runs, then the conflict remains pending and no local write or upload occurs.
 * - Given SKIP_FOR_NOW is chosen for a file, when other files are resolved, then skipped files remain pending for review.
 * - Given configuration or resolver setup fails, when resolution runs, then the result is a provider-level WebDAV result.
 *
 * Observable outcomes:
 * - WebDavSyncResult subtype, pending conflict store contents, state-holder value, local markdown/media writes, remote put/get calls, and memo refresh side effects.
 *
 * TDD proof:
 * - RED: the MERGE_TEXT budget scenario returned WebDavSyncResult.Error("Unable to merge conflict...") instead of preserving a pending conflict.
 *
 * Excludes:
 * - WebDAV transport internals, planner/metadata persistence internals, and UI rendering.
 */
class WebDavConflictResolverTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("resolveConflicts KEEP_LOCAL uploads local content to remote") { `resolveConflicts KEEP_LOCAL uploads local content to remote`() }

        test("resolveConflicts KEEP_REMOTE memo writes markdown content to MAIN directory") { `resolveConflicts KEEP_REMOTE memo writes markdown content to MAIN directory`() }

        test("resolveConflicts KEEP_REMOTE media writes bytes into local media store") { `resolveConflicts KEEP_REMOTE media writes bytes into local media store`() }

        test("resolveConflicts KEEP_REMOTE media reloads raw bytes when conflict payload has no text body") { `resolveConflicts KEEP_REMOTE media reloads raw bytes when conflict payload has no text body`() }

        test("resolveConflicts MERGE_TEXT uploads and writes merged memo content") { `resolveConflicts MERGE_TEXT uploads and writes merged memo content`() }

        test("resolveConflicts MERGE_TEXT keeps over-budget memo conflict pending without writing or uploading") { `resolveConflicts MERGE_TEXT keeps over-budget memo conflict pending without writing or uploading`() }

        test("resolveConflicts returns NotConfigured when config is missing") { `resolveConflicts returns NotConfigured when config is missing`() }

        test("resolveConflicts keeps Success result when refresh fails") { `resolveConflicts keeps Success result when refresh fails`() }

        test("resolveConflicts maps resolver exception to WebDavSyncResult Error") { `resolveConflicts maps resolver exception to WebDavSyncResult Error`() }

        test("resolveConflicts keeps skipped webdav files pending and returns conflict result") { `resolveConflicts keeps skipped webdav files pending and returns conflict result`() }

        test("resolveReview invalidates pending descriptor before applying choices") {
            `resolveReview invalidates pending descriptor before applying choices`()
        }
    }


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

    private fun setUp() {
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
        resolver =
            WebDavConflictResolver(
                runtime = runtime,
                support = support,
                fileBridge = fileBridge,
                pendingConflictStore = InMemoryPendingSyncConflictStore(),
                lifecycleRunner = testRemoteSyncLifecycleRunner(),
            )
    }

    private fun `resolveConflicts KEEP_LOCAL uploads local content to remote`() =
        runTest {
            val path = "lomo/memo/2026_03_24.md"
            val file = conflictFile(path = path, local = "LOCAL", remote = "REMOTE")
            val resolution = SyncConflictResolution(perFileChoices = mapOf(path to SyncConflictResolutionChoice.KEEP_LOCAL))

            val result = resolver.resolveConflicts(resolution, conflictSet(file))

            result shouldBe WebDavSyncResult.Success("Conflicts resolved")
            verify(exactly = 1) {
                client.putSmallFile(
                    path = path,
                    bytes = "LOCAL".toByteArray(StandardCharsets.UTF_8),
                    contentType = WEBDAV_MARKDOWN_CONTENT_TYPE,
                    lastModifiedHint = null,
                    expectedEtag = null,
                    requireAbsent = false,
                )
            }
            coVerify(exactly = 1) { memoSynchronizer.refresh() }
            (stateHolder.state.value is WebDavSyncState.Success).shouldBeTrue()
        }

    private fun `resolveConflicts KEEP_REMOTE memo writes markdown content to MAIN directory`() =
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

            result shouldBe WebDavSyncResult.Success("Conflicts resolved")
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

    private fun `resolveConflicts KEEP_REMOTE media writes bytes into local media store`() =
        runTest {
            val path = "lomo/images/a.png"
            val file = conflictFile(path = path, local = "LOCAL", remote = "REMOTE")
            every { fileBridge.isMemoPath(path, any()) } returns false
            val resolution = SyncConflictResolution(perFileChoices = mapOf(path to SyncConflictResolutionChoice.KEEP_REMOTE))

            val result = resolver.resolveConflicts(resolution, conflictSet(file))

            result shouldBe WebDavSyncResult.Success("Conflicts resolved")
            coVerify(exactly = 1) {
                localMediaSyncStore.writeBytes(
                    relativePath = path,
                    bytes = "REMOTE".toByteArray(StandardCharsets.UTF_8),
                    layout = any<SyncDirectoryLayout>(),
                )
            }
            coVerify(exactly = 0) { markdownStorageDataSource.saveFileIn(any(), any(), any(), any(), any()) }
        }

    private fun `resolveConflicts KEEP_REMOTE media reloads raw bytes when conflict payload has no text body`() =
        runTest {
            val path = "lomo/images/raw.bin"
            val remoteBytes = byteArrayOf(0x00, 0x7f, 0x42, 0xff.toByte())
            val file =
                SyncConflictFile(
                    relativePath = path,
                    localContent = null,
                    remoteContent = null,
                    isBinary = true,
            )
            every { fileBridge.isMemoPath(path, any()) } returns false
            every { client.getToFile(path, any()) } answers {
                secondArg<java.io.File>().writeBytes(remoteBytes)
                com.lomo.data.webdav.WebDavRemoteResource(path, isDirectory = false, etag = "etag-2", lastModified = 222L)
            }
            val resolution = SyncConflictResolution(perFileChoices = mapOf(path to SyncConflictResolutionChoice.KEEP_REMOTE))

            val result = resolver.resolveConflicts(resolution, conflictSet(file))

            result shouldBe WebDavSyncResult.Success("Conflicts resolved")
            verify(exactly = 1) { client.getToFile(path, any()) }
            coVerify(exactly = 1) { localMediaSyncStore.importFromFile(path, any(), any()) }
        }

    private fun `resolveConflicts MERGE_TEXT uploads and writes merged memo content`() =
        runTest {
            val path = "lomo/memo/2026_03_24.md"
            val merged = "start\nlocal\nmiddle\nremote\nend"
            val file =
                conflictFile(
                    path = path,
                    local = "start\nlocal\nmiddle\nend",
                    remote = "start\nmiddle\nremote\nend",
                )
            every { fileBridge.isMemoPath(path, any()) } returns true
            every { fileBridge.extractMemoFilename(path, any()) } returns "2026_03_24.md"
            coEvery {
                markdownStorageDataSource.saveFileIn(
                    directory = com.lomo.data.source.MemoDirectoryType.MAIN,
                    filename = "2026_03_24.md",
                    content = merged,
                    append = false,
                    uri = null,
                )
            } returns null
            val resolution = SyncConflictResolution(perFileChoices = mapOf(path to SyncConflictResolutionChoice.MERGE_TEXT))

            val result = resolver.resolveConflicts(resolution, conflictSet(file))

            result shouldBe WebDavSyncResult.Success("Conflicts resolved")
            verify(exactly = 1) {
                client.putSmallFile(
                    path = path,
                    bytes = merged.toByteArray(StandardCharsets.UTF_8),
                    contentType = WEBDAV_MARKDOWN_CONTENT_TYPE,
                    lastModifiedHint = null,
                    expectedEtag = null,
                    requireAbsent = false,
                )
            }
            coVerify(exactly = 1) {
                markdownStorageDataSource.saveFileIn(
                    directory = com.lomo.data.source.MemoDirectoryType.MAIN,
                    filename = "2026_03_24.md",
                    content = merged,
                    append = false,
                    uri = null,
                )
            }
        }

    private fun `resolveConflicts MERGE_TEXT keeps over-budget memo conflict pending without writing or uploading`() =
        runTest {
            val path = "lomo/memo/2026_03_25.md"
            val file =
                conflictFile(
                    path = path,
                    local = numberedLines(prefix = "local"),
                    remote = numberedLines(prefix = "remote"),
                )
            val pendingStore = InMemoryPendingSyncConflictStore()
            val resolver = WebDavConflictResolver(runtime, support, fileBridge, pendingStore, testRemoteSyncLifecycleRunner())
            every { fileBridge.isMemoPath(path, any()) } returns true
            every { fileBridge.extractMemoFilename(path, any()) } returns "2026_03_25.md"
            val resolution = SyncConflictResolution(perFileChoices = mapOf(path to SyncConflictResolutionChoice.MERGE_TEXT))

            val result = resolver.resolveConflicts(resolution, conflictSet(file))

            val expected = conflictSet(file)
            result shouldBe WebDavSyncResult.Conflict("Pending conflicts remain", expected)
            verify(exactly = 0) {
                client.putSmallFile(
                    path = any(),
                    bytes = any(),
                    contentType = any(),
                    lastModifiedHint = any(),
                    expectedEtag = any(),
                    requireAbsent = any(),
                )
            }
            coVerify(exactly = 0) {
                markdownStorageDataSource.saveFileIn(
                    directory = com.lomo.data.source.MemoDirectoryType.MAIN,
                    filename = "2026_03_25.md",
                    content = any(),
                    append = false,
                    uri = null,
                )
            }
            pendingStore.storedConflict(SyncBackendType.WEBDAV) shouldBe expected
            stateHolder.state.value shouldBe WebDavSyncState.ConflictDetected(expected)
        }

    private fun `resolveConflicts returns NotConfigured when config is missing`() =
        runTest {
            every { dataStore.webDavSyncEnabled } returns flowOf(false)
            val file = conflictFile(path = "lomo/memo/2026_03_24.md", local = "L", remote = "R")

            val result = resolver.resolveConflicts(SyncConflictResolution(emptyMap()), conflictSet(file))

            result shouldBe WebDavSyncResult.NotConfigured
            stateHolder.state.value shouldBe WebDavSyncState.NotConfigured
            verify(exactly = 0) { clientFactory.create(any(), any(), any()) }
        }

    private fun `resolveConflicts keeps Success result when refresh fails`() =
        runTest {
            val path = "lomo/memo/2026_03_24.md"
            val file = conflictFile(path = path, local = "LOCAL", remote = "REMOTE")
            coEvery { memoSynchronizer.refresh() } throws IllegalStateException("refresh failed")

            val result = resolver.resolveConflicts(SyncConflictResolution(emptyMap()), conflictSet(file))

            result shouldBe WebDavSyncResult.Success("Conflicts resolved")
            (stateHolder.state.value is WebDavSyncState.Success).shouldBeTrue()
        }

    private fun `resolveConflicts maps resolver exception to WebDavSyncResult Error`() =
        runTest {
            every { clientFactory.create(any(), any(), any()) } throws IllegalStateException("client init failed")
            val file = conflictFile(path = "lomo/memo/2026_03_24.md", local = "LOCAL", remote = "REMOTE")

            val result = resolver.resolveConflicts(SyncConflictResolution(emptyMap()), conflictSet(file))

            (result is WebDavSyncResult.Error).shouldBeTrue()
            result as WebDavSyncResult.Error
            (result.message.contains("client init failed")).shouldBeTrue()
            (result.exception is IllegalStateException).shouldBeTrue()
            (stateHolder.state.value is WebDavSyncState.Error).shouldBeTrue()
        }

    private fun `resolveConflicts keeps skipped webdav files pending and returns conflict result`() =
        runTest {
            val keptPath = "lomo/memo/2026_03_24.md"
            val skippedPath = "lomo/memo/2026_03_25.md"
            val pendingStore = InMemoryPendingSyncConflictStore()
            every { fileBridge.contentTypeForPath(keptPath, any()) } returns "text/plain"
            val resolver = WebDavConflictResolver(runtime, support, fileBridge, pendingStore, testRemoteSyncLifecycleRunner())
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
                    conflictSet =
                        SyncConflictSet(
                            source = SyncBackendType.WEBDAV,
                            files =
                                listOf(
                                    conflictFile(path = keptPath, local = "LOCAL", remote = "REMOTE"),
                                    conflictFile(path = skippedPath, local = "LEFT", remote = "RIGHT"),
                                ),
                            timestamp = 1L,
                        ),
                )

            result shouldBe WebDavSyncResult.Conflict(
                    "Pending conflicts remain",
                    SyncConflictSet(
                        source = SyncBackendType.WEBDAV,
                        files = listOf(conflictFile(path = skippedPath, local = "LEFT", remote = "RIGHT")),
                        timestamp = 1L,
                    ),
                )
            pendingStore.storedConflict(SyncBackendType.WEBDAV) shouldBe SyncConflictSet(
                    source = SyncBackendType.WEBDAV,
                    files = listOf(conflictFile(path = skippedPath, local = "LEFT", remote = "RIGHT")),
                    timestamp = 1L,
                )
        }

    private fun `resolveReview invalidates pending descriptor before applying choices`() =
        runTest {
            val path = "lomo/memo/2026_03_25.md"
            val review =
                SyncReviewSession(
                    source = SyncBackendType.WEBDAV,
                    items =
                        listOf(
                            SyncReviewItem(
                                relativePath = path,
                                localContent = "local",
                                incomingContent = "incoming",
                                isBinary = false,
                                localLastModified = 10L,
                                incomingLastModified = 20L,
                                state = SyncReviewItemState.READY_TO_IMPORT,
                            ),
                        ),
                    timestamp = 99L,
                    kind = SyncReviewSessionKind.INITIAL_IMPORT_PREVIEW,
                )
            val pendingStore = WebDavTrackingPendingSyncReviewStore()
            pendingStore.descriptor =
                PendingSyncReviewDescriptor(
                    source = SyncBackendType.WEBDAV,
                    workspaceGeneration = "test",
                    kind = review.kind,
                    items =
                        listOf(
                            PendingSyncReviewItemDescriptor(
                                relativePath = path,
                                isBinary = false,
                                local =
                                    PendingSyncSideMetadata(
                                        locator = path,
                                        contentHash = "hash-local",
                                        lastModified = 10L,
                                        size = 5L,
                                        etag = "etag-local",
                                    ),
                                incoming =
                                    PendingSyncSideMetadata(
                                        locator = path,
                                        contentHash = "hash-incoming",
                                        lastModified = 20L,
                                        size = 8L,
                                        etag = null,
                                    ),
                                state = SyncReviewItemState.READY_TO_IMPORT,
                            ),
                        ),
                    timestamp = review.timestamp,
                    validationStatus = PendingSyncValidationStatus.PENDING_RELOAD,
                )
            val resolver =
                WebDavReviewResolver(
                    runtime = runtime,
                    support = support,
                    fileBridge = fileBridge,
                    pendingReviewStore = pendingStore,
                    lifecycleRunner = testRemoteSyncLifecycleRunner(),
                )
            val resolution = SyncReviewResolution(perItemChoices = mapOf(path to SyncReviewResolutionChoice.KEEP_INCOMING))

            val result = resolver.resolveReview(resolution, review)

            result shouldBe WebDavSyncResult.Error("Pending WebDAV review session requires rebuild: STALE_LOCAL")
            pendingStore.clearCalls shouldBe listOf(SyncBackendType.WEBDAV)
            verify(exactly = 0) { client.getSmallFile(any()) }
            verify(exactly = 0) { client.putSmallFile(any(), any(), any(), any(), any(), any()) }
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

    private fun numberedLines(prefix: String): String =
        (0..1_000).joinToString("\n") { index -> "$prefix-$index" }
}

private class WebDavTrackingPendingSyncReviewStore : PendingSyncReviewStore {
    var descriptor: PendingSyncReviewDescriptor? = null
    val clearCalls = mutableListOf<SyncBackendType>()

    override suspend fun readDescriptor(source: SyncBackendType): PendingSyncReviewDescriptor? = descriptor

    override suspend fun write(review: SyncReviewSession) = Unit

    override suspend fun clear(source: SyncBackendType) {
        clearCalls += source
    }
}
