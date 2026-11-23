package com.lomo.data.source

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.io.File

/*
 * Behavior Contract:
 * Capability: provider image inventory preserves logical workspace paths across media layouts; owner: data; P1.
 * Scenarios:
 * - Given independent roots, when scanned, then legacy paths coexist with mounted images and stale images are hidden.
 * - Given a shared root or no selection, when scanned, then physical workspace paths occur once without extra prefixes.
 * Observable outcomes: complete logical-path to provider-location pairs; opaque document IDs retained.
 * TDD proof: target/lomo/reports/manual/directory-layout/data-red.log lacks workspace media; the shared-root case preserves existing inventory behavior;
 * GREEN: target/lomo/reports/manual/directory-layout/data-green.log.
 * TDD proof (immutable scan selection): Regression preservation - data-green.log in
 * target/lomo/reports/manual/directory-selection retains all logical-path/location assertions.
 * Test Change Justification:
 * Reason category: immutable scan inputs.
 * Old behavior/assertion being replaced: fixtures supplied settings via a resolver read during the scan.
 * Why old assertion is no longer correct: each scan must receive a captured directory selection.
 * Coverage preserved by: all independent, shared and unconfigured result maps are unchanged.
 * Why this is not fitting the test to the implementation: preventing cross-selection reads is the product invariant.
 * Excludes: provider implementation and image classification (backend seam supplies observed files).
 */
class MixedDirectoryImageInventoryTest :
    FunSpec({
        test("given independent image storage when scanned then only configured image directory is included") {
            runTest {
                val legacy = "media/old.png" to "content://provider/document/opaque-old"
                val selected = "photo.png" to "content://provider/document/opaque-selected"
                val resolver = mockk<FileStorageBackendResolver>()
                io.mockk.every { resolver.mediaRootAt("/notes") } returns inventoryRoot(
                    "/notes",
                    listOf(legacy, "images/stale.png" to "content://provider/document/old-images"),
                )
                every { resolver.mediaRootAt("/pictures") } returns inventoryRoot("/pictures", listOf(selected))
                FileMediaStorageDataSourceDelegate(
                    resolver,
                ).listImageFiles(MediaStorageSelection("/notes", "/pictures")).toMap() shouldBe mapOf(
                    "images/photo.png" to selected.second,
                    "photo.png" to selected.second,
                )
            }
        }
        test("given shared image storage when scanned then workspace paths are scanned directly without remounting") {
            runTest {
                val files = listOf("images/a.png" to "content://provider/document/opaque-a")
                val root = inventoryRoot("/notes", files)
                val resolver = mockk<FileStorageBackendResolver>()
                io.mockk.every { resolver.mediaRootAt("/notes") } returns root
                FileMediaStorageDataSourceDelegate(
                    resolver,
                ).listImageFiles(MediaStorageSelection("/notes", "/notes")).toMap() shouldBe mapOf(
                    "images/a.png" to "content://provider/document/opaque-a",
                    "a.png" to "content://provider/document/opaque-a",
                )
            }
        }
        test("given no image selection when scanned then neither images nor workspace directories are scanned") {
            runTest {
                val files = listOf("media/a.png" to "content://provider/document/opaque-a")
                val resolver = mockk<FileStorageBackendResolver>()
                io.mockk.every { resolver.mediaRootAt("/notes") } returns inventoryRoot("/notes", files)
                FileMediaStorageDataSourceDelegate(
                    resolver,
                ).listImageFiles(MediaStorageSelection("/notes", null)) shouldBe emptyList()
            }
        }
    })

private fun inventoryRoot(path: String, files: List<Pair<String, String>>): ResolvedMediaRoot = ResolvedMediaRoot(
    object : MediaStorageBackend {
        override suspend fun listImageFiles(): List<Pair<String, String>> = files
        override suspend fun getImageLocation(filename: String): String? = files.toMap()[filename]
    },
    WorkspaceVfs.Direct(File(path)),
)
