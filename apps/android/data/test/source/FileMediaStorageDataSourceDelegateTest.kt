/*
 * Behavior Contract:
 * Capability: dedicated images expose canonical mounted paths and bare display aliases; owner: data; P1.
 * Scenarios:
 * - Given a dedicated image root without notes, when listed, then images/cover.jpg and its bare alias name the observed file.
 * Observable outcomes: logical-path listing and exact location.
 * TDD proof: target/lomo/reports/manual/directory-layout/data-red.log (MixedDirectoryImageInventoryTest) exposes the former selected-only inventory;
 * GREEN: target/lomo/reports/manual/directory-layout/data-green.log.
 * Test Change Justification:
 * Reason category: directory identity contract correction.
 * Old behavior/assertion being replaced: selected-only listings and a source filename lookup with no production callers.
 * Why old assertion is no longer correct: inventory carries the engine namespace; the unused source lookup is removed while backend/provider lookups remain covered.
 * Coverage preserved by: original bare-file location plus canonical mounted location and the independent/shared-root scenarios.
 * Why this is not fitting the test to the implementation: the expected path matches the existing native images/ mount and authored Markdown.
 * Test Change Justification:
 * Reason category: immutable scan input migration.
 * Old behavior/assertion being replaced: settings were read through resolvedMediaRoot during I/O.
 * Why old assertion is no longer correct: the scan is now bound to its captured selection.
 * Coverage preserved by: both mounted and bare alias assertions are retained verbatim.
 * Why this is not fitting the test to the implementation: directory identity cannot change halfway through loading.
 * Excludes: native media mutation, provider traversal and decoding.
 */

package com.lomo.data.source

import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

class FileMediaStorageDataSourceDelegateTest : DataFunSpec() {
    init {
        test("given configured image root when files are listed then resolved backend result returns") {
            runTest {
                val root = Files.createTempDirectory("file-media-storage-list").toFile()
                val backend = fakeBackend(imageFiles = listOf("cover.jpg" to "file:///images/cover.jpg"))
                val resolver = mockk<FileStorageBackendResolver>()
                every { resolver.mediaRootAt(root.path) } returns
                    ResolvedMediaRoot(backend, WorkspaceVfs.Direct(root))

                FileMediaStorageDataSourceDelegate(
                    resolver,
                ).listImageFiles(MediaStorageSelection(null, root.path, null)) shouldBe
                    listOf("images/cover.jpg" to "file:///images/cover.jpg", "cover.jpg" to "file:///images/cover.jpg")

                root.deleteRecursively()
            }
        }
    }
}

private fun fakeBackend(imageFiles: List<Pair<String, String>> = emptyList()): MediaStorageBackend =
    object : MediaStorageBackend {
        override suspend fun listImageFiles(): List<Pair<String, String>> = imageFiles

        override suspend fun getImageLocation(filename: String): String? = imageFiles.toMap()[filename]
    }
