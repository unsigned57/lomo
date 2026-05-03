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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: FileStorageBackendResolver
 * - Behavior focus: root-backend selection, cache invalidation on root changes, media-root precedence, and voice fallback behavior.
 * - Observable outcomes: resolved backend types, returned configured SAF uri markers, nullability for missing roots, and cache reuse vs refresh.
 * - Red phase: Fails before the fix because the resolver does not expose an explicit WorkspaceVfs
 *   shape for resolved roots, leaving the media bridge to branch on concrete backend types.
 * - Excludes: SAF document traversal, backend file I/O, and DataStore persistence implementation internals.
 */
class FileStorageBackendResolverTest {
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

    @Before
    fun setUp() {
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

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
        uriCache.clear()
    }

    @Test
    fun `markdown and workspace backends are null when no root is configured`() =
        runTest {
            assertNull(resolver.markdownBackend())
            assertNull(resolver.workspaceBackend())
        }

    @Test
    fun `markdown backend uses direct backend cache until root configuration changes`() =
        runTest {
            rootDirectory.value = "/vault/root-a"

            val first = resolver.markdownBackend()
            val second = resolver.markdownBackend()

            rootDirectory.value = "/vault/root-b"
            val refreshed = resolver.markdownBackend()

            assertTrue(first?.javaClass?.simpleName == "VfsStorageBackend")
            assertSame(first, second)
            assertTrue(refreshed?.javaClass?.simpleName == "VfsStorageBackend")
            assertNotSame(first, refreshed)
        }

    @Test
    fun `workspace backend prefers saf root when root uri is configured`() =
        runTest {
            rootDirectory.value = "/vault/fallback"
            rootUri.value = "content://tree/root"

            val backend = resolver.workspaceBackend()
            val markdownBackend = resolver.markdownBackend()

            assertTrue(backend?.javaClass?.simpleName == "VfsStorageBackend")
            assertSame(backend, markdownBackend)
        }

    @Test
    fun `root vfs prefers saf root when root uri is configured`() =
        runTest {
            rootDirectory.value = "/vault/fallback"
            rootUri.value = "content://tree/root"

            val vfs = resolver.rootVfs()

            assertTrue(vfs is WorkspaceVfs.Saf)
            assertSame(parsedUri("content://tree/root"), (vfs as WorkspaceVfs.Saf).rootUri)
        }

    @Test
    fun `media and voice backends prefer typed roots and fall back to main root`() =
        runTest {
            rootDirectory.value = "/vault/root"
            imageUri.value = "content://tree/images"

            val imageRoot = resolver.resolvedMediaRoot(StorageRootType.IMAGE)
            val voiceBackend = resolver.voiceBackend()

            assertTrue(imageRoot?.backend?.javaClass?.simpleName == "VfsStorageBackend")
            assertTrue(voiceBackend?.javaClass?.simpleName == "VfsStorageBackend")
            assertTrue(imageRoot?.configuredUriMarker == "content://tree/images")
        }

    @Test
    fun `media backend returns direct backend and null marker for typed directory root`() =
        runTest {
            imageDirectory.value = "/typed/images"

            val imageRoot = resolver.resolvedMediaRoot(StorageRootType.IMAGE)

            assertTrue(imageRoot?.backend?.javaClass?.simpleName == "VfsStorageBackend")
            assertNull(imageRoot?.configuredUriMarker)
        }

    @Test
    fun `resolved media root exposes direct workspace vfs for typed directory root`() =
        runTest {
            imageDirectory.value = "/typed/images"

            val resolvedRoot = resolver.resolvedMediaRoot(StorageRootType.IMAGE)

            assertTrue(resolvedRoot != null)
            assertTrue(resolvedRoot?.backend?.javaClass?.simpleName == "VfsStorageBackend")
            assertTrue(resolvedRoot?.vfs is WorkspaceVfs.Direct)
            assertEquals("/typed/images", (resolvedRoot?.vfs as WorkspaceVfs.Direct).rootDir.path)
            assertNull(resolvedRoot.configuredUriMarker)
        }

    @Test
    fun `media backend prefers typed uri when both typed uri and typed path are configured`() =
        runTest {
            imageUri.value = "content://tree/images"
            imageDirectory.value = "/typed/images"

            val imageRoot = resolver.resolvedMediaRoot(StorageRootType.IMAGE)

            assertTrue(imageRoot?.backend?.javaClass?.simpleName == "VfsStorageBackend")
            assertTrue(imageRoot?.configuredUriMarker == "content://tree/images")
        }

    @Test
    fun `voice backend prefers typed voice root over main root fallback`() =
        runTest {
            rootDirectory.value = "/vault/root"
            voiceDirectory.value = "/typed/voice"

            val voiceBackend = resolver.voiceBackend()
            val rootMedia = resolver.resolvedMediaRoot(StorageRootType.MAIN)

            assertTrue(voiceBackend?.javaClass?.simpleName == "VfsStorageBackend")
            assertTrue(rootMedia?.backend?.javaClass?.simpleName == "VfsStorageBackend")
            assertNotSame(rootMedia?.backend, voiceBackend)
        }

    @Test
    fun `voice backend falls back to saf main root when typed voice root is missing`() =
        runTest {
            rootUri.value = "content://tree/main"

            val voiceBackend = resolver.voiceBackend()

            assertTrue(voiceBackend?.javaClass?.simpleName == "VfsStorageBackend")
        }

    private fun parsedUri(value: String): Uri =
        uriCache.getOrPut(value) {
            mockk<Uri>(relaxed = true).apply {
                every { scheme } returns value.substringBefore(':', "")
                every { path } returns value.substringAfter("://", value)
            }
        }
}
