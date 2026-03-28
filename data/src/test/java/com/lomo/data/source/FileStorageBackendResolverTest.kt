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
 * - Red phase: Not applicable - test-only coverage addition; no production change.
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

            assertTrue(first is DirectStorageBackend)
            assertSame(first, second)
            assertTrue(refreshed is DirectStorageBackend)
            assertNotSame(first, refreshed)
        }

    @Test
    fun `workspace backend prefers saf root when root uri is configured`() =
        runTest {
            rootDirectory.value = "/vault/fallback"
            rootUri.value = "content://tree/root"

            val backend = resolver.workspaceBackend()
            val markdownBackend = resolver.markdownBackend()

            assertTrue(backend is SafStorageBackend)
            assertSame(backend, markdownBackend)
        }

    @Test
    fun `media and voice backends prefer typed roots and fall back to main root`() =
        runTest {
            rootDirectory.value = "/vault/root"
            imageUri.value = "content://tree/images"

            val (imageBackend, imageMarker) = resolver.mediaBackend(StorageRootType.IMAGE)
            val voiceBackend = resolver.voiceBackend()

            assertTrue(imageBackend is SafStorageBackend)
            assertTrue(voiceBackend is DirectStorageBackend)
            assertTrue(imageMarker == "content://tree/images")
        }

    @Test
    fun `media backend returns direct backend and null marker for typed directory root`() =
        runTest {
            imageDirectory.value = "/typed/images"

            val (imageBackend, imageMarker) = resolver.mediaBackend(StorageRootType.IMAGE)

            assertTrue(imageBackend is DirectStorageBackend)
            assertNull(imageMarker)
        }

    @Test
    fun `media backend prefers typed uri when both typed uri and typed path are configured`() =
        runTest {
            imageUri.value = "content://tree/images"
            imageDirectory.value = "/typed/images"

            val (imageBackend, imageMarker) = resolver.mediaBackend(StorageRootType.IMAGE)

            assertTrue(imageBackend is SafStorageBackend)
            assertTrue(imageMarker == "content://tree/images")
        }

    @Test
    fun `voice backend prefers typed voice root over main root fallback`() =
        runTest {
            rootDirectory.value = "/vault/root"
            voiceDirectory.value = "/typed/voice"

            val voiceBackend = resolver.voiceBackend()

            assertTrue(voiceBackend is DirectStorageBackend)
            val (rootMediaBackend, _) = resolver.mediaBackend(StorageRootType.MAIN)
            assertTrue(rootMediaBackend is DirectStorageBackend)
            assertNotSame(rootMediaBackend, voiceBackend)
        }

    @Test
    fun `voice backend falls back to saf main root when typed voice root is missing`() =
        runTest {
            rootUri.value = "content://tree/main"

            val voiceBackend = resolver.voiceBackend()

            assertTrue(voiceBackend is SafStorageBackend)
        }

    private fun parsedUri(value: String): Uri =
        uriCache.getOrPut(value) {
            mockk<Uri>(relaxed = true).apply {
                every { scheme } returns value.substringBefore(':', "")
                every { path } returns value.substringAfter("://", value)
            }
        }
}
