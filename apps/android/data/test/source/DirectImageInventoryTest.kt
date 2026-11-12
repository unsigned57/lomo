package com.lomo.data.source

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import java.net.URI
import kotlin.io.path.createTempDirectory

/*
 * Behavior Contract:
 * Capability: Direct inventory observes nested workspace images without private lifecycle files; owner: data; P1.
 * Scenarios:
 * - Given root and nested images plus staged/private images, when scanned, then only public files return with exact qualified paths.
 * Observable outcomes: relative names and URI round-trips to existing files containing spaces/hash.
 * TDD proof: target/lomo/reports/manual/directory-layout/data-red.log establishes the missing workspace-inventory capability;
 * GREEN: target/lomo/reports/manual/directory-layout/data-green.log exercises the filesystem adapter as well as the logical merge.
 * Excludes: Rust media classification (stubbed boundary) and provider permissions.
 */
class DirectImageInventoryTest :
    FunSpec({
        test("given nested and private images when scanned then public paths retain exact filesystem identity") {
            runTest {
                val root = createTempDirectory("lomo-direct-images-").toFile()
                mockkStatic("com.lomo.data.source.MediaStorageClassificationKt")
                every { isImageAttachmentFileName(any()) } answers { firstArg<String>().endsWith(".png") }
                try {
                    val visible = listOf("root.png", "media/photo #1.png", "image/nested/old.png")
                    val hidden = listOf(".lomo-media-stage/staged.png", ".lomo/history/old.png", ".git/object.png")
                    (visible + hidden).forEach { path ->
                        root.resolve(path).apply {
                            requireNotNull(parentFile).mkdirs()
                            writeText("image")
                        }
                    }
                    val files = DirectMediaStorageBackendDelegate(root).listImageFiles().toMap()
                    files.keys shouldBe visible.toSet()
                    files.forEach { (path, uri) -> URI(uri).path shouldBe root.resolve(path).path }
                } finally {
                    unmockkStatic("com.lomo.data.source.MediaStorageClassificationKt")
                    root.deleteRecursively()
                }
            }
        }
    })
