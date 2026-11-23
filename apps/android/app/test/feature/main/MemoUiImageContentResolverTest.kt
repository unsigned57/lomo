package com.lomo.app.feature.main

import android.net.Uri
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.io.path.createTempDirectory

/*
 * Behavior Contract:
 * Capability: Bare image names use the selected directory; qualified paths retain workspace identity and absent selection preserves unique-claim lookup.
 * Owning layer / priority: MemoUiImageContentResolver; app; P1.
 * Scenarios:
 * - Given an explicit image directory and an old workspace claimant, when resolving a bare name, then only the selected directory answers.
 * - Given no selected directory, when multiple managed files claim a basename, then it stays unresolved.
 * - Given qualified or escaping paths, when resolving, then directory identity and traversal rejection are preserved.
 * - Given changed content identities, when resolving, then content-keyed cache results change.
 * Observable outcomes: returned locations, emitted paging/map state, retained identities and surfaced errors.
 * TDD proof: target/lomo/reports/manual/media-toolbar-search/image-red.log: expected selected photo.png path but received raw photo.png; image-space-red.log rejects duplicate URI/disk claims for a path with spaces; GREEN image-green.log: 11/11.
 * Excludes: physical device timing, Compose pixel styling and remote provider behavior.
 * Test Change Justification:
 * Reason category: regression coverage and platform fixture correction.
 * Old behavior/assertion being replaced: no existing result assertions are removed or weakened.
 * Why old assertion is no longer correct: Android Uri.parse is unavailable in JVM tests; URI fixtures now use a strict Uri mock exposing the same toString value.
 * Coverage preserved by: every existing scenario plus the explicit directory/query regressions.
 * Why this is not fitting the test to the implementation: expected results follow selected-directory identity and normalized-query behavior.
 */
class MemoUiImageContentResolverTest : AppFunSpec() {
    init {
        test(
            "given a selected directory containing spaces when map and disk name the same file then they form one claim",
        ) {
            val selected = createTempDirectory(prefix = "lomo selected image ").toFile()
            try {
                val file = File(selected, "photo.png").apply { writeText("new") }
                val uri = "file://${file.absolutePath.replace(" ", "%20")}#lomo-cid=abc"
                val resolved = MemoUiImageContentResolver().resolveProjectedImageUrls(
                    imageUrls = listOf("photo.png"),
                    rootPath = null,
                    imagePath = selected.absolutePath,
                    imageMap = mapOf("photo.png" to uri.testUri()),
                )
                resolved.single() shouldBe uri
            } finally {
                selected.deleteRecursively()
            }
        }

        test(
            "given a selected image directory when a bare name also exists in the workspace then the selected file is shown",
        ) {
            val workspace = createTempDirectory(prefix = "lomo-selected-image-").toFile()
            val selected = createTempDirectory(prefix = "lomo-selected-root-").toFile()
            try {
                File(workspace, "media").mkdirs()
                val old = File(workspace, "media/photo.png").apply { writeText("old") }
                val fresh = File(selected, "photo.png").apply { writeText("new") }
                val resolved = MemoUiImageContentResolver().resolveProjectedImageUrls(
                    imageUrls = listOf("photo.png"),
                    rootPath = workspace.absolutePath,
                    imagePath = selected.absolutePath,
                    imageMap = mapOf("media/photo.png" to old.toURI().toString().testUri()),
                )
                resolved.single() shouldBe fresh.absolutePath
            } finally {
                workspace.deleteRecursively()
                selected.deleteRecursively()
            }
        }

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
                    imageMap = mapOf("img_1.png" to "$location#lomo-cid=${"a".repeat(64)}".testUri()),
                )
            val second =
                resolver.resolveProjectedImageUrls(
                    imageUrls = listOf("img_1.png"),
                    rootPath = directory.absolutePath,
                    imagePath = directory.absolutePath,
                    imageMap = mapOf("img_1.png" to "$location#lomo-cid=${"b".repeat(64)}".testUri()),
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

        test("same basename at workspace root and managed media root fails closed instead of choosing one") {
            val directory = createTempDirectory(prefix = "lomo-image-ambiguous-").toFile()
            File(directory, "media").apply { mkdirs() }
            File(directory, "pic.png").apply { writeText("root") }
            File(directory, "media/pic.png").apply { writeText("a") }
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

        test("unconfigured images directory is ignored when scanning for a bare basename") {
            val directory = createTempDirectory(prefix = "lomo-image-unconfigured-").toFile()
            File(directory, "media").apply { mkdirs() }
            File(directory, "images").apply { mkdirs() }
            val managedFile = File(directory, "media/pic.png").apply { writeText("a") }
            File(directory, "images/pic.png").apply { writeText("ignored") }
            val resolver = MemoUiImageContentResolver()

            val resolved =
                resolver.resolveProjectedImageUrls(
                    imageUrls = listOf("pic.png"),
                    rootPath = directory.absolutePath,
                    imagePath = null,
                    imageMap = emptyMap(),
                )

            resolved.single() shouldBe managedFile.absolutePath
            directory.deleteRecursively()
        }

        test("basename present in map and on disk under different directories fails closed") {
            val directory = createTempDirectory(prefix = "lomo-image-pool-").toFile()
            File(directory, "pic.png").apply { writeText("root") }
            val resolver = MemoUiImageContentResolver()
            val mapped = "file:///provider/media/pic.png".testUri()

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
            val mapped = "file:///provider/docs/pic.png".testUri()

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
                    "img_a.png" to "file:///media/img_a.png#lomo-cid=${"a".repeat(64)}".testUri(),
                    "img_b.png" to "file:///media/img_b.png#lomo-cid=${"b".repeat(64)}".testUri(),
                )
            val beforeA = buildImageMapDependencySignatureForPaths(setOf("img_a.png"), imageMap)
            val beforeB = buildImageMapDependencySignatureForPaths(setOf("img_b.png"), imageMap)

            val changed =
                imageMap + ("img_a.png" to "file:///media/img_a.png#lomo-cid=${"c".repeat(64)}".testUri())
            val afterA = buildImageMapDependencySignatureForPaths(setOf("img_a.png"), changed)
            val afterB = buildImageMapDependencySignatureForPaths(setOf("img_b.png"), changed)

            afterA shouldNotBe beforeA
            afterB shouldBe beforeB
        }
    }
}

private fun String.testUri(): Uri {
    val raw = this
    val uri = mockk<Uri>()
    every { uri.toString() } returns raw
    return uri
}
