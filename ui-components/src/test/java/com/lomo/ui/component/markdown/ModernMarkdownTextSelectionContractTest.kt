package com.lomo.ui.component.markdown

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: Modern markdown free-text-selection contract.
 * - Behavior focus: when free-text selection is enabled, both pending fallback text and the ready
 *   render-plan path must receive the selection flag directly, without a root SelectionContainer
 *   around the whole renderer that can conflict with platform TextView selection.
 * - Observable outcomes: required selection-flag forwarding snippets remain present and the root
 *   SelectionContainer wrapper is absent in ModernMarkdownRenderer.kt.
 * - Red phase: Fails before the fix because ModernMarkdownRenderer still wraps the shared
 *   contentRenderer in a root SelectionContainer instead of relying on leaf renderers to own text
 *   selection.
 * - Excludes: platform selection handle visuals, TextView internals, and markdown library rendering details.
 */
class ModernMarkdownTextSelectionContractTest {
    private val sourceFile =
        resolveModuleRoot("ui-components")
            .resolve("src/main/java/com/lomo/ui/component/markdown/ModernMarkdownRenderer.kt")

    @Test
    fun `modern markdown forwards selection to leaf renderers without a root wrapper`() {
        val content = sourceFile.readText().normalizeWhitespace()

        assertTrue(
            """
            Free-text copy must be forwarded into both fallback and ready markdown renderers without
            a root SelectionContainer around the whole component:
            ${sourceFile.path}
            """.trimIndent(),
            REQUIRED_FORWARDING_SNIPPETS.all(content::contains),
        )
        assertTrue(
            "ModernMarkdownRenderer must not wrap the whole renderer in a root SelectionContainer.",
            ROOT_SELECTION_WRAPPER !in content,
        )
    }

    private fun String.normalizeWhitespace(): String = replace(Regex("\\s+"), " ").trim()

    private fun resolveModuleRoot(moduleName: String): File {
        val currentDirPath = System.getProperty("user.dir") ?: "."
        val currentDir = File(currentDirPath)
        val candidateRoots =
            listOf(
                currentDir,
                currentDir.resolve(moduleName),
            )
        return checkNotNull(
            candidateRoots.firstOrNull { dir ->
                dir.name == moduleName && dir.resolve("build.gradle.kts").exists()
            },
        ) {
            "Failed to resolve $moduleName module root from $currentDirPath"
        }
    }

    private companion object {
        val REQUIRED_FORWARDING_SNIPPETS =
            listOf(
                "MarkdownRendererFallback(",
                "enableTextSelection = enableTextSelection,",
                "ModernMarkdownRenderPlanContent(",
                "enableTextSelection = enableTextSelection,",
            )
        const val ROOT_SELECTION_WRAPPER = "if (enableTextSelection) { SelectionContainer { contentRenderer() } } else { contentRenderer() }"
    }
}
