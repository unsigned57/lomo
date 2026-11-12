package com.lomo.app.feature.main

import android.net.Uri
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlin.io.path.createTempDirectory

/*
 * Behavior Contract:
 * Capability: existing qualified attachments coexist with dedicated and shared image roots; owner: app; P1.
 * Scenarios:
 * - Given legacy media/image paths and a dedicated image root, when rendered, then workspace files retain identity.
 * - Given one root shared by notes and images, when images/a.png renders, then its physical images directory remains.
 * - Given SAF paths, when rendered, then observed provider locations answer and missing paths never fabricate document IDs.
 * Observable outcomes: resolved file paths; no rebasing onto the wrong directory.
 * TDD proof: target/lomo/reports/manual/directory-layout/app-red.log fails both scenarios before the directory fix;
 * GREEN: target/lomo/reports/manual/directory-layout/app-green.log.
 * Excludes: provider grants, image decoding and native projection indexing.
 */
class MixedMediaDirectoryLayoutTest : AppFunSpec() {
    init {
        test("given opaque SAF documents when rendered then only observed provider locations are used") {
            val old = mockk<Uri>()
            val fresh = mockk<Uri>()
            every { old.toString() } returns "content://provider/document/opaque-old"
            every { fresh.toString() } returns "content://provider/document/opaque-new"
            MemoUiImageContentResolver().resolveProjectedImageUrls(
                listOf("media/old.png", "images/new.png", "images/missing.png"),
                "content://provider/tree/notes",
                "content://provider/tree/pictures",
                mapOf("media/old.png" to old, "images/new.png" to fresh),
            ) shouldBe listOf(old.toString(), fresh.toString(), "images/missing.png")
        }

        test("given legacy qualified images and dedicated storage when rendered then workspace identity is retained") {
            val workspace = createTempDirectory("lomo-mixed-notes-").toFile()
            val selected = createTempDirectory("lomo-mixed-images-").toFile()
            try {
                val paths = listOf("media/old.png", "image/older.png")
                paths.forEach { path ->
                    workspace.resolve(path).apply {
                        requireNotNull(parentFile).mkdirs()
                        writeText("workspace")
                    }
                    selected.resolve(
                        path,
                    ).apply {
                        requireNotNull(parentFile).mkdirs()
                        writeText("wrong selected file")
                    }
                }
                MemoUiImageContentResolver().resolveProjectedImageUrls(
                    paths,
                    workspace.path,
                    selected.path,
                    emptyMap(),
                ) shouldBe paths.map { workspace.resolve(it).path }
            } finally {
                workspace.deleteRecursively()
                selected.deleteRecursively()
            }
        }

        test("given shared notes and image storage when rendering then the images prefix is preserved") {
            val workspace = createTempDirectory("lomo-shared-images-").toFile()
            try {
                val image = workspace.resolve(
                    "images/a.png",
                ).apply {
                    requireNotNull(parentFile).mkdirs()
                    writeText("image")
                }
                workspace.resolve("a.png").writeText("wrong root file")
                MemoUiImageContentResolver().resolveProjectedImageUrls(
                    listOf("images/a.png"),
                    workspace.path,
                    workspace.path,
                    emptyMap(),
                ).single() shouldBe image.path
            } finally {
                workspace.deleteRecursively()
            }
        }
    }
}
