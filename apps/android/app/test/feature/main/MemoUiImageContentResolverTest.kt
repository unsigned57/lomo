package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.io.path.createTempDirectory

/*
 * Behavior Contract:
 * - Unit under test: MemoUiImageContentResolver
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: materialize each (url, roots, this-url mapping) once per resolver lifetime.
 *
 * Scenarios:
 * - Given a relative image that exists on disk, when resolved twice after the file is deleted,
 *   then the second call returns the first materialized path.
 *
 * Observable outcomes: resolved absolute path identity across calls.
 *
 * TDD proof: Fails if the second resolve re-stats the deleted file and falls back to the raw url.
 *
 * Excludes: Coil decoding, SAF tree URI construction, and markdown IR rewriting.
 */
class MemoUiImageContentResolverTest : AppFunSpec() {
    init {
        test("image path resolution reuses the materialized path after the file disappears") {
            val directory = createTempDirectory(prefix = "lomo-image-resolve-").toFile()
            val file = File(directory, "img_1.png").apply { writeText("pixel") }
            val resolver = MemoUiImageContentResolver()

            val first =
                resolver.resolveProjectedImageUrls(
                    imageUrls = listOf("img_1.png"),
                    rootPath = directory.absolutePath,
                    imagePath = directory.absolutePath,
                    imageMap = emptyMap(),
                )
            file.delete()
            val second =
                resolver.resolveProjectedImageUrls(
                    imageUrls = listOf("img_1.png"),
                    rootPath = directory.absolutePath,
                    imagePath = directory.absolutePath,
                    imageMap = emptyMap(),
                )

            first.single() shouldBe file.absolutePath
            second.single() shouldBe first.single()
            directory.delete()
        }
    }
}
