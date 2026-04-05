package com.lomo.app.feature.main

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: MemoListContent image preloading contract.
 * - Behavior focus: initial list entry must preload image URLs from the current visible memo range before or alongside lookahead items, so first-screen images do not wait for a later pass to begin loading.
 * - Observable outcomes: required visible-range preload source snippets remain present in MemoListContent.kt.
 * - Red phase: Fails before the fix because the preload candidate range starts strictly after the visible window, so only off-screen lookahead images are warmed and first-screen images stay cold on app entry.
 * - Excludes: Coil cache internals, network or disk decode latency, and LazyColumn rendering itself.
 */
class MemoListVisibleImagePreloadContractTest {
    private val sourceFile =
        resolveModuleRoot("app").resolve("src/main/java/com/lomo/app/feature/main/MemoListContent.kt")

    @Test
    fun `memo list preload includes the current visible range`() {
        val content = sourceFile.readText().normalizeWhitespace()

        assertTrue(
            """
            Main-list image preload must include the current visible memo range instead of starting
            only after the viewport. Expected visible-range preload snippets in:
            ${sourceFile.path}
            """.trimIndent(),
            REQUIRED_VISIBLE_PRELOAD_SNIPPETS.all(content::contains),
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
        val REQUIRED_VISIBLE_PRELOAD_SNIPPETS =
            listOf(
                "val visibleEndExclusive = firstVisible + visibleCount",
                "(firstVisible until visibleEndExclusive)",
                "val lookaheadStart = visibleEndExclusive",
                "preloadGate.selectUrlsToEnqueue(preloadCandidates)",
            )
    }
}
