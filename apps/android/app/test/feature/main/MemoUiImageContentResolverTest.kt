package com.lomo.app.feature.main

import androidx.core.net.toUri
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
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
 * Test Change Justification:
 * - Reason category: product/domain contract changed.
 * - Old behavior/assertion being replaced: resolved destinations keyed by path only.
 * - Why old assertion is no longer correct: content identity (#lomo-cid=) now participates in the resolved destination so same-path byte changes invalidate caches.
 * - Coverage preserved by: the added same-path/different-content-id case plus existing path cases.
 * - Why this is not fitting the test to the implementation: content-keyed cache invalidation is the media-identity contract.
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

        test("same path with different content ids resolves to different destinations") {
            val directory = createTempDirectory(prefix = "lomo-image-content-").toFile()
            val file = File(directory, "img_1.png").apply { writeText("pixel") }
            val resolver = MemoUiImageContentResolver()
            val location = "file://${file.absolutePath}"

            val first =
                resolver.resolveProjectedImageUrls(
                    imageUrls = listOf("img_1.png"),
                    rootPath = directory.absolutePath,
                    imagePath = directory.absolutePath,
                    imageMap = mapOf("img_1.png" to "$location#lomo-cid=${"a".repeat(64)}".toUri()),
                )
            val second =
                resolver.resolveProjectedImageUrls(
                    imageUrls = listOf("img_1.png"),
                    rootPath = directory.absolutePath,
                    imagePath = directory.absolutePath,
                    imageMap = mapOf("img_1.png" to "$location#lomo-cid=${"b".repeat(64)}".toUri()),
                )

            first.single() shouldNotBe second.single()
            first.single() shouldContain "lomo-cid=${"a".repeat(64)}"
            second.single() shouldContain "lomo-cid=${"b".repeat(64)}"
            directory.delete()
        }

        test("per-path dependency signature only moves for the memo that references the changed image") {
            val imageMap =
                mapOf(
                    "img_a.png" to "file:///media/img_a.png#lomo-cid=${"a".repeat(64)}".toUri(),
                    "img_b.png" to "file:///media/img_b.png#lomo-cid=${"b".repeat(64)}".toUri(),
                )
            val beforeA = buildImageMapDependencySignatureForPaths(setOf("img_a.png"), imageMap)
            val beforeB = buildImageMapDependencySignatureForPaths(setOf("img_b.png"), imageMap)

            val changed =
                imageMap + ("img_a.png" to "file:///media/img_a.png#lomo-cid=${"c".repeat(64)}".toUri())
            val afterA = buildImageMapDependencySignatureForPaths(setOf("img_a.png"), changed)
            val afterB = buildImageMapDependencySignatureForPaths(setOf("img_b.png"), changed)

            afterA shouldNotBe beforeA
            afterB shouldBe beforeB
        }
    }
}
