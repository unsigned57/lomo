package com.lomo.ui.text

import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: memo paragraph link-visibility contract.
 * - Behavior focus: paragraphs rendered through platform TextView must align link text color with the resolved paragraph text color so a memo consisting only of a URL remains visible instead of depending on platform-default link colors.
 * - Observable outcomes: source-level presence of explicit link-text-color alignment in MemoParagraphAppearance.kt.
 * - Red phase: Fails before the fix because link-bearing memo paragraphs leave TextView link color at the platform default, so a URL-only memo can appear blank when every visible glyph is styled as a link.
 * - Excludes: Android span drawing internals, theme resource resolution, clickable navigation dispatch, and Compose layout.
 */
class MemoParagraphLinkVisibilityContractTest {
    private val sourceText: String by lazy {
        Paths
            .get("src/main/java/com/lomo/ui/text/MemoParagraphAppearance.kt")
            .toFile()
            .readText()
    }

    @Test
    fun `link bearing memo paragraphs align link color with resolved text color`() {
        assertTrue(
            """
            URL-only memo paragraphs must explicitly align TextView link color with the resolved
            paragraph text color so the entire line stays visible even when every glyph is a link.
            """.trimIndent(),
            sourceText.contains("setLinkTextColor(currentTextColor)"),
        )
    }
}
