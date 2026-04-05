package com.lomo.ui.component.card

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: memo-card free-text-copy contract.
 * - Behavior focus: when free-text copy is enabled on memo cards, the body content must forward the
 *   flag into leaf renderers without adding a root SelectionContainer that can compete with platform
 *   TextView selection.
 * - Observable outcomes: root shared-selection wrapper is absent and the free-copy flag is still
 *   forwarded into memo markdown content from MemoCardBodyContent.kt.
 * - Red phase: Fails before the fix because MemoCardBodyContent still wraps the whole body in a root
 *   SelectionContainer, which is the implementation policy most likely to break long-press text
 *   selection after the renderer switched to Android TextView leaves.
 * - Excludes: platform selection handle visuals, Compose gesture dispatch internals, and TextView rendering behavior.
 */
class MemoCardFreeTextCopyContractTest {
    private val sourceFile =
        resolveModuleRoot("ui-components")
            .resolve("src/main/java/com/lomo/ui/component/card/MemoCardBodyContent.kt")

    @Test
    fun `memo card body avoids a root selection wrapper and forwards free-copy to leaf content`() {
        val content = sourceFile.readText().normalizeWhitespace()

        assertTrue(
            """
            Main-list memo bodies must avoid a root SelectionContainer when free-text copy is
            enabled, and instead keep forwarding the flag into the child markdown path so Android
            TextView leaves own the long-press selection gesture:
            ${sourceFile.path}
            """.trimIndent(),
            REQUIRED_FORWARDING_SNIPPETS.all(content::contains),
        )
        assertTrue(
            "MemoCardBodyContent must not wrap the whole body in a root SelectionContainer.",
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
                "MemoCardMarkdownContent(",
                "allowFreeTextCopy = allowFreeTextCopy,",
            )
        const val ROOT_SELECTION_WRAPPER = "if (allowFreeTextCopy) { SelectionContainer { bodyContent() } } else { bodyContent() }"
    }
}
