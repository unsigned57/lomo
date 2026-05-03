package com.lomo.app.feature.main

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: active Paging main-list host state creation.
 * - Behavior focus: the real main-screen LazyListState must allocate an explicit cache window so
 *   rows just outside the viewport stay prepared while delete collapse pulls them into view.
 * - Observable outcomes: required and forbidden source snippets in MainScreenStateHosts.kt.
 * - Red phase: Fails before the fix because rememberMainScreenHostState still creates the default
 *   LazyListState with the stock Saver and no cache window.
 * - Excludes: runtime Compose frame interpolation, exact cache-size tuning, and Trash screen state.
 */
class MainListViewportEntryCacheContractTest {
    private val moduleRoot = resolveModuleRoot("app")
    private val sourceFile =
        moduleRoot.resolve("src/main/java/com/lomo/app/feature/main/MainScreenStateHosts.kt")

    @Test
    fun `main paging list state keeps an explicit viewport cache window`() {
        val content = sourceFile.readText().normalizeWhitespace()

        assertTrue(
            """
            MainScreen should build its active Paging list state with an explicit cache window in
            ${sourceFile.path} so rows entering from just outside the viewport do not appear only
            at their final settled position.
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
                "LazyLayoutCacheWindow(",
                "aheadFraction =",
                "behindFraction =",
                "cacheWindow = MAIN_MEMO_LIST_CACHE_WINDOW",
                "MainMemoListStateSaver",
            )

        val FORBIDDEN_SNIPPETS =
            listOf(
                "rememberSaveable(saver = androidx.compose.foundation.lazy.LazyListState.Saver)",
                "androidx.compose.foundation.lazy.LazyListState()",
            )
    }
}
