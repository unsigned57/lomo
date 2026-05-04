package com.lomo.app.feature.main

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: main-list single-Paging source contract.
 * - Behavior focus: the main screen must stop branching between paged and in-memory list pipelines,
 *   build Paging data from a parameterized main-list source instead of emitting `PagingData.empty()`
 *   for filtered scenarios, and keep cold-start initial loads within a first-screen budget.
 * - Observable outcomes: required and forbidden source snippets across the main-screen state/render files.
 * - Red phase: Fails before the fix because MainListPresentationMode, usesPagedMainList, the
 *   filtered in-memory list path, or an oversized initial Paging load are present in the main-screen sources.
 * - Excludes: Room SQL correctness, Compose runtime animation timing, and repository implementation internals.
 */
class MainListPagingContractTest {
    private val moduleRoot = resolveModuleRoot("app")
    private val stateHolderFile =
        moduleRoot.resolve("src/main/java/com/lomo/app/feature/main/MainMemoListStateHolder.kt")
    private val viewModelFile =
        moduleRoot.resolve("src/main/java/com/lomo/app/feature/main/MainViewModel.kt")
    private val screenFile =
        moduleRoot.resolve("src/main/java/com/lomo/app/feature/main/MainScreen.kt")
    private val stateHostsFile =
        moduleRoot.resolve("src/main/java/com/lomo/app/feature/main/MainScreenStateHosts.kt")
    private val layoutFile =
        moduleRoot.resolve("src/main/java/com/lomo/app/feature/main/MainScreenLayout.kt")
    private val legacyModeFile =
        moduleRoot.resolve("src/main/java/com/lomo/app/feature/main/MainListPresentationMode.kt")

    @Test
    fun `main list keeps only the single paging path`() {
        val mainFiles =
            listOf(stateHolderFile, viewModelFile, screenFile, stateHostsFile, layoutFile)
                .joinToString(separator = " ") { it.readText() }
                .normalizeWhitespace()

        assertTrue(
            """
            Main list should keep one Paging path across:
            ${listOf(stateHolderFile, viewModelFile, screenFile, stateHostsFile, layoutFile).joinToString(separator = "\n") { it.path }}
            and remove the legacy presentation-mode file:
            ${legacyModeFile.path}
            """.trimIndent(),
            legacyModeFile.exists().not() &&
                REQUIRED_SNIPPETS.all(mainFiles::contains) &&
                FORBIDDEN_SNIPPETS.none(mainFiles::contains),
        )
    }

    @Test
    fun `main list initial paging load stays within first screen budget`() {
        val content = stateHolderFile.readText().normalizeWhitespace()

        assertTrue(
            """
            Main list cold start should not load multiple pages before first paint. Keep the initial
            load tied to the page size so Paging can render the first screen before loading more rows.
            """.trimIndent(),
            content.contains("private const val DEFAULT_MAIN_LIST_PAGE_SIZE = 20") &&
                content.contains(
                    "private const val DEFAULT_MAIN_LIST_INITIAL_LOAD_SIZE = DEFAULT_MAIN_LIST_PAGE_SIZE",
                ),
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
                "memoUiCoordinator.mainListPagingSource(",
                "pagingSourceFactory = {",
                "collectAsLazyPagingItems()",
            )

        val FORBIDDEN_SNIPPETS =
            listOf(
                "MainListPresentationMode",
                "usesPagedMainList",
                "PagingData.empty()",
                "val memos: StateFlow<List<Memo>>",
                "val uiMemos: StateFlow<List<MemoUiModel>>",
                "val visibleUiMemos: StateFlow<List<MemoUiModel>>",
                "if (screenState.usesPagedMainList)",
            )
    }
}
