/*
 * Behavior Contract:
 * - Unit under test: FileMediaStorageDataSourceDelegate.
 * - Owning layer: data.
 * - Priority tier: P1.
 * - Capability: resolve the configured image root for read-only image location surfaces.
 *
 * Scenarios:
 * - Given a configured image root, when files are listed, then the resolved backend result returns.
 * - Given a configured image root, when one filename is resolved, then its backend location returns.
 *
 * Observable outcomes:
 * - returned image listing and location.
 *
 * TDD proof:
 * - RED: Stage-7 architecture failed while the interface retained retired save/delete/voice
 *   operations represented by unsupported sentinels.
 *
 * Excludes:
 * - Rust-owned image import/delete, Rust-owned recording allocation/finalize, and SAF traversal.
 *
 * Test Change Justification:
 * - Reason category: Stage-7 tail deletion after the P4-10A media cutover.
 * - Old behavior/assertion being replaced: Kotlin save/delete methods failed closed after cutover.
 * - Why old assertion is no longer correct: an unavailable operation must be absent from the type,
 *   not shipped as a method that always throws.
 * - Coverage preserved by: this test locks retained read surfaces; MediaEdge/Rust tests own import,
 *   recording and media-trash behavior.
 * - Why this is not fitting the test to the implementation: the production owner contract changed
 *   from transitional fail-closed methods to a capability-minimal interface.
 */

package com.lomo.data.source

import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import java.nio.file.Files
import kotlinx.coroutines.test.runTest

class FileMediaStorageDataSourceDelegateTest : DataFunSpec() {
    init {
        test("given configured image root when files are listed then resolved backend result returns") {
            runTest {
                val root = Files.createTempDirectory("file-media-storage-list").toFile()
                val backend = fakeBackend(imageFiles = listOf("cover.jpg" to "file:///images/cover.jpg"))
                val resolver = mockk<FileStorageBackendResolver>()
                coEvery { resolver.resolvedMediaRoot(StorageRootType.IMAGE) } returns
                    ResolvedMediaRoot(backend, WorkspaceVfs.Direct(root), configuredUriMarker = null)

                FileMediaStorageDataSourceDelegate(resolver).listImageFiles() shouldBe
                    listOf("cover.jpg" to "file:///images/cover.jpg")

                root.deleteRecursively()
            }
        }

        test("given configured image root when filename is resolved then backend location returns") {
            runTest {
                val root = Files.createTempDirectory("file-media-storage-location").toFile()
                val backend = fakeBackend(location = "file:///images/cover.jpg")
                val resolver = mockk<FileStorageBackendResolver>()
                coEvery { resolver.resolvedMediaRoot(StorageRootType.IMAGE) } returns
                    ResolvedMediaRoot(backend, WorkspaceVfs.Direct(root), configuredUriMarker = null)

                FileMediaStorageDataSourceDelegate(resolver).getImageLocation("cover.jpg") shouldBe
                    "file:///images/cover.jpg"

                root.deleteRecursively()
            }
        }
    }
}

private fun fakeBackend(
    imageFiles: List<Pair<String, String>> = emptyList(),
    location: String? = null,
): MediaStorageBackend =
    object : MediaStorageBackend {
        override suspend fun listImageFiles(): List<Pair<String, String>> = imageFiles

        override suspend fun getImageLocation(filename: String): String? = location
    }
