/*
 * Behavior Contract:
 * - Unit under test: FileMediaStorageDataSourceDelegate.
 * - Owning layer: data.
 * - Priority tier: P1.
 * - Capability: save imported images through the resolved media backend without callers knowing
 *   Direct/SAF backend differences.
 *
 * Scenarios:
 * - Given a direct media root is resolved, when saveImage runs, then the resolved backend receives
 *   the source URI and generated filename.
 * - Given a SAF media root is resolved, when saveImage runs, then the resolved backend receives the
 *   source URI and generated filename.
 * - Given image listing, location lookup, or deletion is requested, when an image root is configured,
 *   then those operations use the resolved media root backend.
 *
 * Observable outcomes:
 * - generated filename extension, backend save calls, returned file listings/locations, and delete
 *   delegation.
 *
 * TDD proof:
 * - RED: direct backend capability failed first because DirectMediaStorageBackendDelegate had no
 *   Context-backed constructor and saveImage threw UnsupportedOperationException.
 *
 * Excludes:
 * - direct backend file I/O, full SAF traversal, Android permission handling, and image signature
 *   validation internals.
 */

package com.lomo.data.source

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlinx.coroutines.test.runTest

class FileMediaStorageDataSourceDelegateTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        afterTest {
            tearDown()
        }

        test("saveImage delegates direct workspace vfs to resolved backend") { `saveImage delegates direct workspace vfs to resolved backend`() }

        test("saveImage delegates saf workspace vfs to resolved backend") { `saveImage delegates saf workspace vfs to resolved backend`() }

        test("listImageFiles uses resolved media root instead of legacy mediaBackend accessor") { `listImageFiles uses resolved media root instead of legacy mediaBackend accessor`() }

        test("getImageLocation uses resolved media root instead of legacy mediaBackend accessor") { `getImageLocation uses resolved media root instead of legacy mediaBackend accessor`() }

        test("deleteImage uses resolved media root instead of legacy mediaBackend accessor") { `deleteImage uses resolved media root instead of legacy mediaBackend accessor`() }
    }

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var backendResolver: FileStorageBackendResolver
    private lateinit var sourceUri: Uri
    private lateinit var backend: RecordingMediaStorageBackend
    private lateinit var tempDir: java.nio.file.Path

    private fun setUp() {
        context = mockk()
        contentResolver = mockk()
        backendResolver = mockk()
        sourceUri = mockk()
        backend = RecordingMediaStorageBackend()
        tempDir = Files.createTempDirectory("file-media-storage-vfs")
        every { context.contentResolver } returns contentResolver
        every { contentResolver.getType(sourceUri) } returns "image/png"
        every { contentResolver.openInputStream(sourceUri) } returns ByteArrayInputStream(PNG_HEADER)
    }

    private fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    private fun `saveImage delegates direct workspace vfs to resolved backend`() =
        runTest {
            coEvery { backendResolver.resolvedMediaRoot(StorageRootType.IMAGE) } returns
                ResolvedMediaRoot(
                    backend = backend,
                    vfs = WorkspaceVfs.Direct(tempDir.toFile()),
                    configuredUriMarker = null,
                )
            val delegate = FileMediaStorageDataSourceDelegate(context, backendResolver)

            val filename = delegate.saveImage(sourceUri)

            (filename.endsWith(".png")).shouldBeTrue()
            backend.savedImages shouldBe listOf(RecordingMediaStorageBackend.SavedImage(sourceUri, filename))
        }

    private fun `saveImage delegates saf workspace vfs to resolved backend`() =
        runTest {
            val rootUri = mockk<Uri>()
            coEvery { backendResolver.resolvedMediaRoot(StorageRootType.IMAGE) } returns
                ResolvedMediaRoot(
                    backend = backend,
                    vfs = WorkspaceVfs.Saf(rootUri),
                    configuredUriMarker = "content://tree/images",
                )
            val delegate = FileMediaStorageDataSourceDelegate(context, backendResolver)

            val filename = delegate.saveImage(sourceUri)

            (filename.endsWith(".png")).shouldBeTrue()
            backend.savedImages shouldBe listOf(RecordingMediaStorageBackend.SavedImage(sourceUri, filename))
        }

    private fun `listImageFiles uses resolved media root instead of legacy mediaBackend accessor`() =
        runTest {
            backend.imageFiles = listOf("cover.jpg" to "file:///images/cover.jpg")
            coEvery { backendResolver.resolvedMediaRoot(StorageRootType.IMAGE) } returns
                ResolvedMediaRoot(
                    backend = backend,
                    vfs = WorkspaceVfs.Direct(tempDir.toFile()),
                    configuredUriMarker = null,
                )
            val delegate = FileMediaStorageDataSourceDelegate(context, backendResolver)

            val files = delegate.listImageFiles()

            files shouldBe listOf("cover.jpg" to "file:///images/cover.jpg")
        }

    private fun `getImageLocation uses resolved media root instead of legacy mediaBackend accessor`() =
        runTest {
            backend.imageLocations["cover.jpg"] = "file:///images/cover.jpg"
            coEvery { backendResolver.resolvedMediaRoot(StorageRootType.IMAGE) } returns
                ResolvedMediaRoot(
                    backend = backend,
                    vfs = WorkspaceVfs.Direct(tempDir.toFile()),
                    configuredUriMarker = null,
                )
            val delegate = FileMediaStorageDataSourceDelegate(context, backendResolver)

            val location = delegate.getImageLocation("cover.jpg")

            location shouldBe "file:///images/cover.jpg"
        }

    private fun `deleteImage uses resolved media root instead of legacy mediaBackend accessor`() =
        runTest {
            coEvery { backendResolver.resolvedMediaRoot(StorageRootType.IMAGE) } returns
                ResolvedMediaRoot(
                    backend = backend,
                    vfs = WorkspaceVfs.Direct(tempDir.toFile()),
                    configuredUriMarker = null,
                )
            val delegate = FileMediaStorageDataSourceDelegate(context, backendResolver)

            delegate.deleteImage("cover.jpg")

            backend.deletedImages shouldBe listOf("cover.jpg")
        }
}

private class RecordingMediaStorageBackend : MediaStorageBackend {
    data class SavedImage(
        val sourceUri: Uri,
        val filename: String,
    )

    val savedImages = mutableListOf<SavedImage>()
    val deletedImages = mutableListOf<String>()
    val imageLocations = mutableMapOf<String, String>()
    var imageFiles: List<Pair<String, String>> = emptyList()

    override suspend fun saveImage(
        sourceUri: Uri,
        filename: String,
    ) {
        savedImages += SavedImage(sourceUri = sourceUri, filename = filename)
    }

    override suspend fun listImageFiles(): List<Pair<String, String>> = imageFiles

    override suspend fun getImageLocation(filename: String): String? = imageLocations[filename]

    override suspend fun deleteImage(filename: String) {
        deletedImages += filename
    }

    override suspend fun createVoiceFile(filename: String): Uri = error("Voice files are outside this contract")

    override suspend fun deleteVoiceFile(filename: String) {
        error("Voice files are outside this contract")
    }
}

private val PNG_HEADER =
    byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
    )
