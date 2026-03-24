package com.lomo.app.feature.main

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: MemoListContent animation contract
 * - Behavior focus: keep source-level animation constants and snippets that define insert and delete motion.
 * - Observable outcomes: required animation declarations remain present in MemoListContent.kt.
 * - Excludes: runtime Compose rendering, timing interpolation internals, and unrelated list behavior.
 */
class MemoListAnimationContractTest {
    private val sourceFile =
        resolveModuleRoot("app").resolve("src/main/java/com/lomo/app/feature/main/MemoListContent.kt")

    @Test
    fun `memo list keeps new memo enter animation`() {
        val content = sourceFile.readText().normalizeWhitespace()

        assertTrue(
            """
            New memos must keep the staged enter animation in MemoListContent.
            Expected animateItem fade-in keyframes with a delayed reveal and placement spring in:
            ${sourceFile.path}
            """.trimIndent(),
            NEW_MEMO_ENTER_ANIMATION_CONSTANTS.all(content::contains) &&
            NEW_MEMO_ENTER_ANIMATION_SNIPPETS.all(content::contains),
        )
    }

    @Test
    fun `memo list keeps delete fade animation`() {
        val content = sourceFile.readText().normalizeWhitespace()

        assertTrue(
            """
            Deleting memos must keep the fade-out animation in MemoListContent.
            Expected animateFloatAsState(targetValue = if (isDeleting) 0f else 1f)
            followed by graphicsLayer alpha application in:
            ${sourceFile.path}
            """.trimIndent(),
            DELETE_FADE_ANIMATION_CONSTANTS.all(content::contains) &&
            DELETE_FADE_ANIMATION_SNIPPETS.all(content::contains),
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
        val NEW_MEMO_ENTER_ANIMATION_CONSTANTS =
            listOf(
                "private const val MEMO_ITEM_HIDDEN_ALPHA = 0f",
                "private const val MEMO_ITEM_VISIBLE_ALPHA = 1f",
                "private const val MEMO_INSERT_ANIMATION_DURATION_MILLIS = 600",
                "private const val MEMO_INSERT_FADE_DELAY_MILLIS = 300",
            )

        val NEW_MEMO_ENTER_ANIMATION_SNIPPETS =
            listOf(
                ".animateItem(",
                "durationMillis = MEMO_INSERT_ANIMATION_DURATION_MILLIS",
                "MEMO_ITEM_HIDDEN_ALPHA at 0",
                "MEMO_ITEM_HIDDEN_ALPHA at MEMO_INSERT_FADE_DELAY_MILLIS",
                "MEMO_ITEM_VISIBLE_ALPHA at MEMO_INSERT_ANIMATION_DURATION_MILLIS using com.lomo.ui.theme.MotionTokens.EasingEmphasizedDecelerate",
                "fadeOutSpec = null",
                "placementSpec = spring(stiffness = Spring.StiffnessLow)",
            )

        val DELETE_FADE_ANIMATION_CONSTANTS =
            listOf(
                "private const val MEMO_ITEM_ALPHA_THRESHOLD = 0.999f",
                "private const val MEMO_DELETE_ANIMATION_DURATION_MILLIS = 300",
            )

        val DELETE_FADE_ANIMATION_SNIPPETS =
            listOf(
                "animateFloatAsState(",
                "targetValue = if (isDeleting) { MEMO_ITEM_HIDDEN_ALPHA } else { MEMO_ITEM_VISIBLE_ALPHA }",
                "durationMillis = MEMO_DELETE_ANIMATION_DURATION_MILLIS",
                "label = \"DeleteAlpha\"",
                "if (deleteAlpha < MEMO_ITEM_ALPHA_THRESHOLD)",
                "Modifier.graphicsLayer { alpha = deleteAlpha compositingStrategy = CompositingStrategy.ModulateAlpha }",
            )
    }
}
