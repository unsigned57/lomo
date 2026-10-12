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
 * - Capability: materialize each (url, roots, this-url mapping) once per resolver lifetime;
 *   bare basenames resolve through the managed directory vocabulary with unique-claim
 *   semantics, qualified destinations read verbatim, and ambiguous/escaped destinations
 *   never resolve to a file.
 *
 * Scenarios:
 * - Given a relative image that exists on disk, when resolved twice after the file is deleted,
 *   then the second call returns the first materialized path.
 * - Given a bare basename with one nested managed file, when resolved, then that file answers.
 * - Given a bare basename claimed by two managed locations (map or disk), when resolved,
 *   then the raw destination answers — never a chosen directory.
 * - Given a qualified destination whose file lives elsewhere, when resolved, then it misses
 *   verbatim — a directory-qualified path is an identity, not a lookup request.
 * - Given a `..` destination escaping the workspace, when resolved, then no file answers.
 *
 * Observable outcomes: resolved absolute path identity across calls; unresolved raw
 * destination for ambiguous/missing/escaped inputs.
 *
 * TDD proof: Fails if the second resolve re-stats the deleted file and falls back to the raw url.
 *
 * Excludes: Coil decoding, SAF tree URI construction, and markdown IR rewriting.
 * Test Change Justification:
 * - Reason category: product/domain contract changed (P-56 basename→directory resolution).
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

        test("bare basename resolves the unique nested managed file") {
            val directory = createTempDirectory(prefix = "lomo-image-nested-").toFile()
            val nested = File(directory, "media/photos").apply { mkdirs() }
            val file = File(nested, "pic.png").apply { writeText("pixel") }
            val resolver = MemoUiImageContentResolver()

            val resolved =
                resolver.resolveProjectedImageUrls(
                    imageUrls = listOf("pic.png"),
                    rootPath = directory.absolutePath,
                    imagePath = null,
                    imageMap = emptyMap(),
                )

            resolved.single() shouldBe file.absolutePath
            directory.deleteRecursively()
        }

        test("same basename under two managed roots fails closed instead of choosing one") {
            val directory = createTempDirectory(prefix = "lomo-image-ambiguous-").toFile()
            File(directory, "media").apply { mkdirs() }
            File(directory, "images").apply { mkdirs() }
            File(directory, "media/pic.png").apply { writeText("a") }
            File(directory, "images/pic.png").apply { writeText("b") }
            val resolver = MemoUiImageContentResolver()

            val resolved =
                resolver.resolveProjectedImageUrls(
                    imageUrls = listOf("pic.png"),
                    rootPath = directory.absolutePath,
                    imagePath = null,
                    imageMap = emptyMap(),
                )

            // Fail-closed: the raw destination answers — no directory wins.
            resolved.single() shouldBe "pic.png"
            directory.deleteRecursively()
        }

        test("basename present in map and on disk under different directories fails closed") {
            val directory = createTempDirectory(prefix = "lomo-image-pool-").toFile()
            File(directory, "images").apply { mkdirs() }
            File(directory, "images/pic.png").apply { writeText("b") }
            val resolver = MemoUiImageContentResolver()
            val mapped = "file:///provider/media/pic.png".toUri()

            val resolved =
                resolver.resolveProjectedImageUrls(
                    imageUrls = listOf("pic.png"),
                    rootPath = directory.absolutePath,
                    imagePath = null,
                    imageMap = mapOf("media/pic.png" to mapped),
                )

            resolved.single() shouldBe "pic.png"
            directory.deleteRecursively()
        }

        test("a map key outside the managed vocabulary never claims a bare basename") {
            val directory = createTempDirectory(prefix = "lomo-image-vocab-").toFile()
            val resolver = MemoUiImageContentResolver()
            val mapped = "file:///provider/docs/pic.png".toUri()

            val resolved =
                resolver.resolveProjectedImageUrls(
                    imageUrls = listOf("pic.png"),
                    rootPath = directory.absolutePath,
                    imagePath = null,
                    imageMap = mapOf("docs/pic.png" to mapped),
                )

            // `docs/` is not a managed attachment root: the vocabulary, not the
            // map, bounds what a bare basename may claim. Missing falls back to
            // the root-relative display path — never the docs URI.
            resolved.single() shouldBe File(directory, "pic.png").absolutePath
            directory.deleteRecursively()
        }

        test("qualified destination reads verbatim even when the name exists elsewhere") {
            val directory = createTempDirectory(prefix = "lomo-image-qualified-").toFile()
            File(directory, "images").apply { mkdirs() }
            File(directory, "images/pic.png").apply { writeText("wrong-dir") }
            val resolver = MemoUiImageContentResolver()

            val resolved =
                resolver.resolveProjectedImageUrls(
                    imageUrls = listOf("media/pic.png"),
                    rootPath = directory.absolutePath,
                    imagePath = null,
                    imageMap = emptyMap(),
                )

            // `media/pic.png` is an identity: it misses on disk and never
            // relocates to `images/pic.png`.
            resolved.single() shouldBe File(directory, "media/pic.png").absolutePath
            directory.deleteRecursively()
        }

        test("parent-directory escape never resolves outside the workspace") {
            val directory = createTempDirectory(prefix = "lomo-image-escape-").toFile()
            val outside = File(directory.parentFile, "escaped-${directory.name}.png")
            outside.writeText("outside")
            try {
                val resolver = MemoUiImageContentResolver()
                val resolved =
                    resolver.resolveProjectedImageUrls(
                        imageUrls = listOf("../escaped-${directory.name}.png"),
                        rootPath = directory.absolutePath,
                        imagePath = null,
                        imageMap = emptyMap(),
                    )

                resolved.single() shouldBe "../escaped-${directory.name}.png"
            } finally {
                outside.delete()
                directory.deleteRecursively()
            }
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
