package com.lomo.app.feature.main

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
        val NEW_MEMO_ENTER_ANIMATION_SNIPPETS =
            listOf(
                ".animateItem(",
                "durationMillis = 600 0f at 0 0f at 300 1f at 600 using com.lomo.ui.theme.MotionTokens.EasingEmphasizedDecelerate",
                "fadeOutSpec = null",
                "placementSpec = spring(stiffness = Spring.StiffnessLow)",
            )

        val DELETE_FADE_ANIMATION_SNIPPETS =
            listOf(
                "animateFloatAsState(",
                "targetValue = if (isDeleting) 0f else 1f",
                "durationMillis = 300",
                "label = \"DeleteAlpha\"",
                "if (deleteAlpha < 0.999f)",
                "Modifier.graphicsLayer { alpha = deleteAlpha compositingStrategy = CompositingStrategy.ModulateAlpha }",
            )
    }
}
