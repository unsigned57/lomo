package com.lomo.app.feature.memo

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

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
class MemoEditorPreviewContentResolverTest {
    @Test
    fun `build memo editor preview content resolves relative markdown image through image map`() {
        val cachedUri = mockk<android.net.Uri>()
        every { cachedUri.toString() } returns "content://images/foo%20bar.png"

        val resolved =
            buildMemoEditorPreviewContent(
                content = "![cover](assets/foo%20bar.png)",
                rootPath = "/memo",
                imagePath = null,
                imageMap = mapOf("foo bar.png" to cachedUri),
            )

        assertEquals("![cover](content://images/foo%20bar.png)", resolved)
    }

    @Test
    fun `build memo editor preview content keeps plain markdown unchanged`() {
        val resolved =
            buildMemoEditorPreviewContent(
                content = "# Title\nBody",
                rootPath = "/memo",
                imagePath = "/images",
                imageMap = emptyMap(),
            )

        assertEquals("# Title\nBody", resolved)
    }
}
