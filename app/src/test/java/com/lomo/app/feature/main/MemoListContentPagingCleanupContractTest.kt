package com.lomo.app.feature.main

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: MemoListContent paging cleanup contract.
 * - Behavior focus: MemoListContent.kt must keep only the shared helpers still imported by
 *   PagedMemoListContent.kt and must not retain the dead ImmutableList-based entrypoint plus its
 *   private render chain after the main screen's Paging migration.
 * - Observable outcomes: required shared symbols remain in MemoListContent.kt, the paging entrypoint
 *   stays in PagedMemoListContent.kt, and forbidden dead-overload snippets disappear from
 *   MemoListContent.kt.
 * - Red phase: Fails before the fix because MemoListContent.kt still contains the dead
 *   MemoListContent(memos: ImmutableList<...>) overload and its MemoListBody/MemoListColumn helpers.
 * - Excludes: Compose runtime behavior, LazyPagingItems rendering details, and animation timing.
 */
/*
 * Test Change Justification:
 * - Reason category: architectural contract replacement.
 * - Old behavior/assertion being replaced: MemoListContent.kt was required to retain
 *   Modifier.memoListPlacementAnimation as the app-local LazyColumn animateItem helper.
 * - Why the old assertion is no longer correct: list row motion now belongs to the shared
 *   ui-components LazyListMotion framework, so retaining an app-local placement helper would
 *   preserve the duplication that caused the regression.
 * - Coverage preserved by: this test still locks the paging-only cleanup, while
 *   LazyListMotionAdoptionContractTest requires app feature lists to adopt lazyListMotionItem.
 * - Why this is not fitting the test to the implementation: the new assertion directly encodes
 *   the requested architecture: app code must not own ad hoc lazy-list movement policy.
 */
class MemoListContentPagingCleanupContractTest {
    private val moduleRoot = resolveModuleRoot("app")
    private val sharedFile =
        moduleRoot.resolve("src/main/java/com/lomo/app/feature/main/MemoListContent.kt")
    private val pagedFile =
        moduleRoot.resolve("src/main/java/com/lomo/app/feature/main/PagedMemoListContent.kt")

    @Test
    fun `memo list source keeps only the paging entrypoint and shared helpers`() {
        val sharedContent = sharedFile.readText().normalizeWhitespace()
        val pagedContent = pagedFile.readText().normalizeWhitespace()

        assertTrue(
            """
            Paging migration cleanup should remove the dead non-paged MemoListContent entrypoint from:
            ${sharedFile.path}
            while preserving shared helpers used by:
            ${pagedFile.path}
            """.trimIndent(),
            REQUIRED_SHARED_SNIPPETS.all(sharedContent::contains) &&
                REQUIRED_PAGED_SNIPPETS.all(pagedContent::contains) &&
                FORBIDDEN_SHARED_SNIPPETS.none(sharedContent::contains),
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
        val REQUIRED_SHARED_SNIPPETS =
            listOf(
                "internal fun MemoListPreloadEffect(",
                "internal fun MemoListItem(",
                "internal fun BoxScope.MemoListPullToRefreshIndicator(",
            )

        val REQUIRED_PAGED_SNIPPETS =
            listOf(
                "internal fun MemoListContent(",
                "pagedMemos: LazyPagingItems<MemoUiModel>",
            )

        val FORBIDDEN_SHARED_SNIPPETS =
            listOf(
                "internal fun MemoListContent( memos: ImmutableList<MemoUiModel>",
                "private fun MemoListBody(",
                "private fun MemoListColumn(",
            )
    }
}
