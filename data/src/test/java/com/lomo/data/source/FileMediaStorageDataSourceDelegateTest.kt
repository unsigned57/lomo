package com.lomo.data.source


import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.nio.file.Files
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.booleans.shouldBeTrue

/*
 * Test Contract:
 * - Unit under test: FileMediaStorageDataSourceDelegate
 * - Behavior focus: saving imported images through resolved storage roots without branching on
 *   concrete backend classes.
 * - Observable outcomes: direct roots write bytes into the resolved directory, SAF roots delegate
 *   the save to the resolved media backend, and filename generation still returns the stored name.
 * - Red phase: Fails before the fix because saveImage switches on DirectStorageBackend /
 *   SafStorageBackend concrete types instead of using an explicit WorkspaceVfs resolution.
 * - Excludes: full SAF traversal, Android permission handling, and image listing/delete behavior.
 */
class FileMediaStorageDataSourceDelegateTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        afterTest {
            tearDown()
        }

        test("saveImage copies bytes into direct workspace vfs without backend type checks") { `saveImage copies bytes into direct workspace vfs without backend type checks`() }

        test("saveImage delegates saf workspace vfs to resolved backend") { `saveImage delegates saf workspace vfs to resolved backend`() }

        test("listImageFiles uses resolved media root instead of legacy mediaBackend accessor") { `listImageFiles uses resolved media root instead of legacy mediaBackend accessor`() }

        test("getImageLocation uses resolved media root instead of legacy mediaBackend accessor") { `getImageLocation uses resolved media root instead of legacy mediaBackend accessor`() }

        test("deleteImage uses resolved media root instead of legacy mediaBackend accessor") { `deleteImage uses resolved media root instead of legacy mediaBackend accessor`() }
    }


    @MockK(relaxed = true)
    private lateinit var context: Context

    @MockK(relaxed = true)
    private lateinit var contentResolver: ContentResolver

    @MockK(relaxed = true)
    private lateinit var backendResolver: FileStorageBackendResolver

    @MockK(relaxed = true)
    private lateinit var sourceUri: Uri

    @MockK(relaxed = true)
    private lateinit var backend: MediaStorageBackend

    private lateinit var tempDir: java.nio.file.Path

    private fun setUp() {
        MockKAnnotations.init(this)
        tempDir = Files.createTempDirectory("file-media-storage-vfs")
        every { context.contentResolver } returns contentResolver
        every { contentResolver.getType(sourceUri) } returns "image/png"
        every { contentResolver.openInputStream(sourceUri) } returns ByteArrayInputStream(PNG_HEADER)
    }

    private fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    private fun `saveImage copies bytes into direct workspace vfs without backend type checks`() =
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
            (tempDir.resolve(filename).toFile().exists()).shouldBeTrue()
            coVerify(exactly = 0) { backend.saveImage(any(), any()) }
        }

    private fun `saveImage delegates saf workspace vfs to resolved backend`() =
        runTest {
            val rootUri = mockk<Uri>(relaxed = true)
            coEvery { backendResolver.resolvedMediaRoot(StorageRootType.IMAGE) } returns
                ResolvedMediaRoot(
                    backend = backend,
                    vfs = WorkspaceVfs.Saf(rootUri),
                    configuredUriMarker = "content://tree/images",
                )
            val delegate = FileMediaStorageDataSourceDelegate(context, backendResolver)

            val filename = delegate.saveImage(sourceUri)

            (filename.endsWith(".png")).shouldBeTrue()
            coVerify(exactly = 1) { backend.saveImage(sourceUri, filename) }
        }

    private fun `listImageFiles uses resolved media root instead of legacy mediaBackend accessor`() =
        runTest {
            coEvery { backendResolver.resolvedMediaRoot(StorageRootType.IMAGE) } returns
                ResolvedMediaRoot(
                    backend = backend,
                    vfs = WorkspaceVfs.Direct(tempDir.toFile()),
                    configuredUriMarker = null,
                )
            coEvery { backend.listImageFiles() } returns listOf("cover.jpg" to "file:///images/cover.jpg")
            val delegate = FileMediaStorageDataSourceDelegate(context, backendResolver)

            val files = delegate.listImageFiles()

            (files == listOf("cover.jpg" to "file:///images/cover.jpg")).shouldBeTrue()
        }

    private fun `getImageLocation uses resolved media root instead of legacy mediaBackend accessor`() =
        runTest {
            coEvery { backendResolver.resolvedMediaRoot(StorageRootType.IMAGE) } returns
                ResolvedMediaRoot(
                    backend = backend,
                    vfs = WorkspaceVfs.Direct(tempDir.toFile()),
                    configuredUriMarker = null,
                )
            coEvery { backend.getImageLocation("cover.jpg") } returns "file:///images/cover.jpg"
            val delegate = FileMediaStorageDataSourceDelegate(context, backendResolver)

            val location = delegate.getImageLocation("cover.jpg")

            (location == "file:///images/cover.jpg").shouldBeTrue()
        }

    private fun `deleteImage uses resolved media root instead of legacy mediaBackend accessor`() =
        runTest {
            coEvery { backendResolver.resolvedMediaRoot(StorageRootType.IMAGE) } returns
                ResolvedMediaRoot(
                    backend = backend,
                    vfs = WorkspaceVfs.Direct(tempDir.toFile()),
                    configuredUriMarker = null,
                )
            coEvery { backend.deleteImage("cover.jpg") } returns Unit
            val delegate = FileMediaStorageDataSourceDelegate(context, backendResolver)

            delegate.deleteImage("cover.jpg")

            coVerify(exactly = 1) { backend.deleteImage("cover.jpg") }
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
