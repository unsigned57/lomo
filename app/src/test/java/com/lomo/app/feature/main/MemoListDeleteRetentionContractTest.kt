package com.lomo.app.feature.main

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: paged MemoListContent delete-retention contract.
 * - Behavior focus: the active Paging entrypoint must retain disappearing rows inside the composable
 *   via rememberRetainedVisibleItems and settle them through an animation callback instead of asking
 *   callers to manage a separate collapsing-id pipeline.
 * - Observable outcomes: required and forbidden source snippets in PagedMemoListContent.kt.
 * - Red phase: Fails before the fix because the active Paging path either lacked retained rows or the
 *   test still pointed at the removed non-paged entrypoint.
 * - Excludes: runtime Compose animation frames, ViewModel wiring, and shared helper implementation details.
 */
class MemoListDeleteRetentionContractTest {
    private val moduleRoot = resolveModuleRoot("app")
    private val sourceFile =
        moduleRoot.resolve("src/main/java/com/lomo/app/feature/main/PagedMemoListContent.kt")

    @Test
    fun `paged memo list keeps retained rows inside the composable`() {
        val content = sourceFile.readText().normalizeWhitespace()

        assertTrue(
            """
            Active Paging MemoListContent should derive a retained visible list inside
            ${sourceFile.path} and stop requiring collapsing ids from callers.
            """.trimIndent(),
            REQUIRED_SNIPPETS.all(content::contains) &&
                FORBIDDEN_SNIPPETS.none(content::contains),
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
        val REQUIRED_SNIPPETS =
            listOf(
                "rememberRetainedVisibleItems(",
                "sourceItems = snapshotMemos",
                "retainedIds = deletingIds",
                "onRetentionSettled = onDeleteAnimationSettled",
                "val visiblePagedMemos =",
            )

        val FORBIDDEN_SNIPPETS =
            listOf(
                "collapsingMemoIds",
                "collapsingIds =",
                "collapsingIds:",
            )
    }
}
