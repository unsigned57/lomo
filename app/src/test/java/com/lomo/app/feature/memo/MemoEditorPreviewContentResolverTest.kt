package com.lomo.app.feature.memo

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

/*
 * Test Contract:
 * - Unit under test: Memo editor preview content resolver bridge in app layer.
 * - Behavior focus: long-form preview must resolve markdown image paths to loadable URLs using the
 *   same image-map rules as memo-card rendering.
 * - Observable outcomes: transformed preview markdown string and unchanged passthrough text.
 * - Red phase: Fails before the fix because editor preview currently renders raw input text without
 *   resolving relative image paths through image-map/root-path dependencies.
 * - Excludes: Compose rendering tree, Coil decode behavior, and media import/storage side effects.
 */
class MemoEditorPreviewContentResolverTest : AppFunSpec() {
    init {
        test("build memo editor preview content resolves relative markdown image through image map") {
            val cachedUri = mockk<android.net.Uri>()
            every { cachedUri.toString() } returns "content://images/foo%20bar.png"

            val resolved =
                buildMemoEditorPreviewContent(
                    content = "![cover](assets/foo%20bar.png)",
                    rootPath = "/memo",
                    imagePath = null,
                    imageMap = mapOf("foo bar.png" to cachedUri),
                )

            (resolved) shouldBe ("![cover](content://images/foo%20bar.png)")
        }
    }

    init {
        test("build memo editor preview content keeps plain markdown unchanged") {
            val resolved =
                buildMemoEditorPreviewContent(
                    content = "# Title\nBody",
                    rootPath = "/memo",
                    imagePath = "/images",
                    imageMap = emptyMap(),
                )

            (resolved) shouldBe ("# Title\nBody")
        }
    }

    init {
        test("build memo editor preview content linkifies geo uri text") {
            val geoUri = "geo:-29.1645,141.5243?z=10"

            val resolved =
                buildMemoEditorPreviewContent(
                    content = "Meet here\n$geoUri",
                    rootPath = "/memo",
                    imagePath = "/images",
                    imageMap = emptyMap(),
                )

            (resolved) shouldBe ("Meet here\n[$geoUri]($geoUri)")
        }
    }

}
