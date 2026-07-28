/*
 * Behavior Contract:
 * - Unit under test: FileMediaStorageDataSourceDelegate.
 * - Owning layer: data.
 * - Priority tier: P1.
 * - Capability: after Wave A, saveImage is retired (Rust MediaEdge owns import); list/get/delete
 *   still use resolved media backends for location surfaces.
 *
 * Scenarios:
 * - Given saveImage is invoked, when Wave A cutover is active, then IOException fails closed.
 * - Given image listing, location lookup, or deletion is requested, when an image root is configured,
 *   then those operations use the resolved media root backend.
 *
 * Observable outcomes:
 * - fail-closed saveImage, returned file listings/locations, and delete delegation.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=data --include-classes='com.lomo.data.source.FileMediaStorageDataSourceDelegateTest'
 * - RED: saveImage still invented basenames/magic identity after Wave A cutover.
 * - GREEN: saveImage fails closed; list/get/delete still use resolved media backends.
 *
 * Excludes:
 * - MediaEdge import path (covered by MediaPort/edge tests), SAF traversal, magic validation.
 *
 * Test Change Justification:
 * - Reason category: production media import ownership moved to MediaEdge / Rust.
 * - Old behavior/assertion being replaced: saveImage wrote files with Kotlin magic/basename identity.
 * - Why old assertion is no longer correct: Wave A cutover retires Kotlin saveImage identity; import
 *   stages through MediaPort with human path suggestions from Rust.
 * - Coverage preserved by: list/get/delete still assert resolved media-root backend delegation.
 * - Why this is not fitting the test to the implementation: locks fail-closed import boundary and
 *   remaining location surfaces, not digest algorithms.
 */

package com.lomo.data.source

import android.content.Context
import android.net.Uri
import com.lomo.data.repository.ProcessWorkspaceMutationLease
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.fakes.FakeEngineReadinessRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
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

        test("saveImage fails closed after Wave A media cutover") {
            `saveImage fails closed after Wave A media cutover`()
        }

        test("listImageFiles uses resolved media root instead of legacy mediaBackend accessor") {
            `listImageFiles uses resolved media root instead of legacy mediaBackend accessor`()
        }

        test("getImageLocation uses resolved media root instead of legacy mediaBackend accessor") {
            `getImageLocation uses resolved media root instead of legacy mediaBackend accessor`()
        }

        test("deleteImage is retired after Wave A and fails closed") {
            `deleteImage is retired after Wave A and fails closed`()
        }
    }

    private lateinit var context: Context
    private lateinit var backendResolver: FileStorageBackendResolver
    private lateinit var sourceUri: Uri
    private lateinit var backend: RecordingMediaStorageBackend
    private lateinit var tempDir: java.nio.file.Path

    private fun setUp() {
        context = mockk()
        backendResolver = mockk()
        sourceUri = mockk()
        backend = RecordingMediaStorageBackend()
        tempDir = Files.createTempDirectory("file-media-storage-vfs")
        every { context.contentResolver } returns mockk(relaxed = true)
    }

    private fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    private fun `saveImage fails closed after Wave A media cutover`() =
        runTest {
            val delegate =
                FileMediaStorageDataSourceDelegate(
                    context,
                    backendResolver,
                    ProcessWorkspaceMutationLease(FakeEngineReadinessRepository()),
                )
            shouldThrow<IOException> {
                delegate.saveImage(sourceUri)
            }
            backend.savedImages shouldBe emptyList()
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
            val delegate =
                FileMediaStorageDataSourceDelegate(
                    context,
                    backendResolver,
                    ProcessWorkspaceMutationLease(FakeEngineReadinessRepository()),
                )

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
            val delegate =
                FileMediaStorageDataSourceDelegate(
                    context,
                    backendResolver,
                    ProcessWorkspaceMutationLease(FakeEngineReadinessRepository()),
                )

            val location = delegate.getImageLocation("cover.jpg")

            location shouldBe "file:///images/cover.jpg"
        }

    private fun `deleteImage is retired after Wave A and fails closed`() =
        runTest {
            coEvery { backendResolver.resolvedMediaRoot(StorageRootType.IMAGE) } returns
                ResolvedMediaRoot(
                    backend = backend,
                    vfs = WorkspaceVfs.Direct(tempDir.toFile()),
                    configuredUriMarker = null,
                )
            val delegate =
                FileMediaStorageDataSourceDelegate(
                    context,
                    backendResolver,
                    ProcessWorkspaceMutationLease(FakeEngineReadinessRepository()),
                )

            shouldThrow<UnsupportedOperationException> {
                delegate.deleteImage("cover.jpg")
            }
            backend.deletedImages shouldBe emptyList()
        }

    private class RecordingMediaStorageBackend : MediaStorageBackend {
        data class SavedImage(
            val uri: Uri,
            val filename: String,
        )

        val savedImages = mutableListOf<SavedImage>()
        var imageFiles: List<Pair<String, String>> = emptyList()
        val imageLocations = linkedMapOf<String, String>()
        val deletedImages = mutableListOf<String>()

        override suspend fun saveImage(
            sourceUri: Uri,
            filename: String,
        ) {
            savedImages += SavedImage(sourceUri, filename)
        }

        override suspend fun listImageFiles(): List<Pair<String, String>> = imageFiles

        override suspend fun getImageLocation(filename: String): String? = imageLocations[filename]

        override suspend fun deleteImage(filename: String) {
            deletedImages += filename
        }

        override suspend fun createVoiceFile(filename: String): Uri = error("unused")

        override suspend fun deleteVoiceFile(filename: String) = Unit
    }
}
