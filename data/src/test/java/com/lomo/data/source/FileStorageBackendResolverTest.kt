package com.lomo.data.source


import android.content.Context
import android.net.Uri
import com.lomo.data.local.datastore.LomoDataStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull

/*
 * Test Contract:
 * - Unit under test: FileStorageBackendResolver
 * - Behavior focus: root-backend selection, cache invalidation on root changes, media-root precedence, and voice fallback behavior.
 * - Observable outcomes: resolved backend types, returned configured SAF uri markers, nullability for missing roots, and cache reuse vs refresh.
 * - Red phase: Fails before the fix because the resolver does not expose an explicit WorkspaceVfs
 *   shape for resolved roots, leaving the media bridge to branch on concrete backend types.
 * - Excludes: SAF document traversal, backend file I/O, and DataStore persistence implementation internals.
 */
class FileStorageBackendResolverTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        afterTest {
            tearDown()
        }

        test("markdown and workspace backends are null when no root is configured") { `markdown and workspace backends are null when no root is configured`() }

        test("markdown backend uses direct backend cache until root configuration changes") { `markdown backend uses direct backend cache until root configuration changes`() }

        test("workspace backend prefers saf root when root uri is configured") { `workspace backend prefers saf root when root uri is configured`() }

        test("root vfs prefers saf root when root uri is configured") { `root vfs prefers saf root when root uri is configured`() }

        test("media and voice backends prefer typed roots and fall back to main root") { `media and voice backends prefer typed roots and fall back to main root`() }

        test("media backend returns direct backend and null marker for typed directory root") { `media backend returns direct backend and null marker for typed directory root`() }

        test("resolved media root exposes direct workspace vfs for typed directory root") { `resolved media root exposes direct workspace vfs for typed directory root`() }

        test("media backend prefers typed uri when both typed uri and typed path are configured") { `media backend prefers typed uri when both typed uri and typed path are configured`() }

        test("voice backend prefers typed voice root over main root fallback") { `voice backend prefers typed voice root over main root fallback`() }

        test("voice backend falls back to saf main root when typed voice root is missing") { `voice backend falls back to saf main root when typed voice root is missing`() }
    }


    private val context = mockk<Context>(relaxed = true)
    private val dataStore = mockk<LomoDataStore>(relaxed = true)
    private val uriCache = linkedMapOf<String, Uri>()

    private val rootUri = MutableStateFlow<String?>(null)
    private val rootDirectory = MutableStateFlow<String?>(null)
    private val imageUri = MutableStateFlow<String?>(null)
    private val imageDirectory = MutableStateFlow<String?>(null)
    private val voiceUri = MutableStateFlow<String?>(null)
    private val voiceDirectory = MutableStateFlow<String?>(null)

    private lateinit var resolver: FileStorageBackendResolver

    private fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers { parsedUri(firstArg()) }
        every { dataStore.rootUri } returns rootUri
        every { dataStore.rootDirectory } returns rootDirectory
        every { dataStore.imageUri } returns imageUri
        every { dataStore.imageDirectory } returns imageDirectory
        every { dataStore.voiceUri } returns voiceUri
        every { dataStore.voiceDirectory } returns voiceDirectory
        resolver = FileStorageBackendResolver(context, dataStore)
    }

    private fun tearDown() {
        unmockkStatic(Uri::class)
        uriCache.clear()
    }

    private fun `markdown and workspace backends are null when no root is configured`() =
        runTest {
            resolver.markdownBackend().shouldBeNull()
            resolver.workspaceBackend().shouldBeNull()
        }

    private fun `markdown backend uses direct backend cache until root configuration changes`() =
        runTest {
            rootDirectory.value = "/vault/root-a"

            val first = resolver.markdownBackend()
            val second = resolver.markdownBackend()

            rootDirectory.value = "/vault/root-b"
            val refreshed = resolver.markdownBackend()

            (first?.javaClass?.simpleName == "VfsStorageBackend").shouldBeTrue()
            (second === first).shouldBeTrue()
            (refreshed?.javaClass?.simpleName == "VfsStorageBackend").shouldBeTrue()
            (refreshed !== first).shouldBeTrue()
        }

    private fun `workspace backend prefers saf root when root uri is configured`() =
        runTest {
            rootDirectory.value = "/vault/fallback"
            rootUri.value = "content://tree/root"

            val backend = resolver.workspaceBackend()
            val markdownBackend = resolver.markdownBackend()

            (backend?.javaClass?.simpleName == "VfsStorageBackend").shouldBeTrue()
            (markdownBackend === backend).shouldBeTrue()
        }

    private fun `root vfs prefers saf root when root uri is configured`() =
        runTest {
            rootDirectory.value = "/vault/fallback"
            rootUri.value = "content://tree/root"

            val vfs = resolver.rootVfs()

            (vfs is WorkspaceVfs.Saf).shouldBeTrue()
            ((vfs as WorkspaceVfs.Saf).rootUri === parsedUri("content://tree/root")).shouldBeTrue()
        }

    private fun `media and voice backends prefer typed roots and fall back to main root`() =
        runTest {
            rootDirectory.value = "/vault/root"
            imageUri.value = "content://tree/images"

            val imageRoot = resolver.resolvedMediaRoot(StorageRootType.IMAGE)
            val voiceBackend = resolver.voiceBackend()

            (imageRoot?.backend?.javaClass?.simpleName == "VfsStorageBackend").shouldBeTrue()
            (voiceBackend?.javaClass?.simpleName == "VfsStorageBackend").shouldBeTrue()
            (imageRoot?.configuredUriMarker == "content://tree/images").shouldBeTrue()
        }

    private fun `media backend returns direct backend and null marker for typed directory root`() =
        runTest {
            imageDirectory.value = "/typed/images"

            val imageRoot = resolver.resolvedMediaRoot(StorageRootType.IMAGE)

            (imageRoot?.backend?.javaClass?.simpleName == "VfsStorageBackend").shouldBeTrue()
            imageRoot?.configuredUriMarker.shouldBeNull()
        }

    private fun `resolved media root exposes direct workspace vfs for typed directory root`() =
        runTest {
            imageDirectory.value = "/typed/images"

            val resolvedRoot = resolver.resolvedMediaRoot(StorageRootType.IMAGE)

            (resolvedRoot != null).shouldBeTrue()
            (resolvedRoot?.backend?.javaClass?.simpleName == "VfsStorageBackend").shouldBeTrue()
            (resolvedRoot?.vfs is WorkspaceVfs.Direct).shouldBeTrue()
            (resolvedRoot?.vfs as WorkspaceVfs.Direct).rootDir.path shouldBe "/typed/images"
            resolvedRoot.configuredUriMarker.shouldBeNull()
        }

    private fun `media backend prefers typed uri when both typed uri and typed path are configured`() =
        runTest {
            imageUri.value = "content://tree/images"
            imageDirectory.value = "/typed/images"

            val imageRoot = resolver.resolvedMediaRoot(StorageRootType.IMAGE)

            (imageRoot?.backend?.javaClass?.simpleName == "VfsStorageBackend").shouldBeTrue()
            (imageRoot?.configuredUriMarker == "content://tree/images").shouldBeTrue()
        }

    private fun `voice backend prefers typed voice root over main root fallback`() =
        runTest {
            rootDirectory.value = "/vault/root"
            voiceDirectory.value = "/typed/voice"

            val voiceBackend = resolver.voiceBackend()
            val rootMedia = resolver.resolvedMediaRoot(StorageRootType.MAIN)

            (voiceBackend?.javaClass?.simpleName == "VfsStorageBackend").shouldBeTrue()
            (rootMedia?.backend?.javaClass?.simpleName == "VfsStorageBackend").shouldBeTrue()
            (voiceBackend !== rootMedia?.backend).shouldBeTrue()
        }

    private fun `voice backend falls back to saf main root when typed voice root is missing`() =
        runTest {
            rootUri.value = "content://tree/main"

            val voiceBackend = resolver.voiceBackend()

            (voiceBackend?.javaClass?.simpleName == "VfsStorageBackend").shouldBeTrue()
        }

    private fun parsedUri(value: String): Uri =
        uriCache.getOrPut(value) {
            mockk<Uri>(relaxed = true).apply {
                every { scheme } returns value.substringBefore(':', "")
                every { path } returns value.substringAfter("://", value)
            }
        }
}
