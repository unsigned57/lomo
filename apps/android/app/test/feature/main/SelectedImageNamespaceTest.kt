package com.lomo.app.feature.main

import android.net.Uri
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.io.path.createTempDirectory

/*
 * Behavior Contract:
 * Capability: images/ follows dedicated storage while other qualified paths keep workspace identity; owner: app; P1.
 * Scenarios:
 * - Given media/ or another qualified workspace path and a selection, when rendered, then the workspace file answers.
 * - Given a selected SAF child, when mapped, then its opaque provider URI answers; a missing mapping stays unresolved.
 * - Given images/ paths, when switching dedicated roots or missing a file, then only the selected root answers.
 * Observable outcomes: exact locations, missing destinations and directory-switch results.
 * TDD proof: target/lomo/reports/manual/directory-layout/app-red.log detects legacy path rebasing and shared-prefix loss;
 * GREEN: target/lomo/reports/manual/directory-layout/app-green.log.
 * Test Change Justification:
 * Reason category: directory identity and provider evidence correction.
 * Old behavior/assertion being replaced: all qualified paths were rebased into selected storage and missing SAF files received guessed document IDs.
 * Why old assertion is no longer correct: native routing mounts only images/; provider IDs are opaque and an absent file cannot have a witnessed URI.
 * Coverage preserved by: dedicated images/ hit/miss across switches, qualified workspace identity, selected SAF child hit and explicit missing case.
 * Why this is not fitting the test to the implementation: expected paths agree with native mounts and provider-issued document identities.
 * Excludes: real provider permissions and remote destinations.
 */
class SelectedImageNamespaceTest : AppFunSpec() {
    init {
        test(
            "given selected image storage when other qualified attachments render then workspace identity is retained",
        ) {
            val workspace = createTempDirectory("lomo-qualified-workspace").toFile()
            val selected = createTempDirectory("lomo-qualified-selected").toFile()
            try {
                for (path in listOf("media/photo.png", "nested/photo.png")) {
                    File(workspace, path).apply {
                        requireNotNull(parentFile).mkdirs()
                        writeText("old")
                    }
                    MemoUiImageContentResolver().resolveProjectedImageUrls(
                        listOf(path),
                        workspace.path,
                        selected.path,
                        emptyMap(),
                    ).single() shouldBe File(workspace, path).path
                }
            } finally {
                workspace.deleteRecursively()
                selected.deleteRecursively()
            }
        }
        test(
            "given a selected SAF child when rendered then observed provider IDs answer and missing paths stay unresolved",
        ) {
            val base = "content://provider/tree/parent/document/parent%2Fimages"
            val selected = mockk<Uri>()
            every { selected.toString() } returns "content://provider/document/opaque-selected-photo"
            val resolver = MemoUiImageContentResolver()
            resolver.resolveProjectedImageUrls(
                listOf("images/photo.png"),
                null,
                base,
                mapOf("images/photo.png" to selected),
            ).single() shouldBe selected.toString()
            resolver.resolveProjectedImageUrls(
                listOf("images/missing.png"),
                null,
                base,
                emptyMap(),
            ).single() shouldBe "images/missing.png"
        }
        test(
            "given selected images when resolving qualified paths then display follows the mount across switches and misses",
        ) {
            val workspace = createTempDirectory("lomo-image-workspace").toFile()
            val selected = createTempDirectory("lomo-image-selected").toFile()
            val next = createTempDirectory("lomo-image-next").toFile()
            try {
                val resolver = MemoUiImageContentResolver()
                for (name in listOf("photo.png", "nested/photo.png")) {
                    File(workspace, "images/$name").apply {
                        requireNotNull(parentFile).mkdirs()
                        writeText("old")
                    }
                    File(selected, name).apply {
                        requireNotNull(parentFile).mkdirs()
                        writeText("selected")
                    }
                    fun resolve(root: File) = resolver.resolveProjectedImageUrls(
                        listOf("images/$name"),
                        workspace.path,
                        root.path,
                        emptyMap(),
                    ).single()
                    resolve(selected) shouldBe File(selected, name).path
                    resolve(next) shouldBe File(next, name).path
                }
            } finally {
                workspace.deleteRecursively()
                selected.deleteRecursively()
                next.deleteRecursively()
            }
        }
    }
}
