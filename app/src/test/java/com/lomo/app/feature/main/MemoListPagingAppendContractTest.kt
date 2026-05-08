package com.lomo.app.feature.main

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: MemoListContent paging append contract.
 * - Behavior focus: the main list must render from LazyPagingItems and read each visible
 *   `pagedMemos[index]` so Paging can dispatch append loads past the first loaded snapshot.
 *   The draggable scrollbar must use the repository-backed total item count, not the currently
 *   loaded Paging snapshot size, so page append does not move the thumb upward.
 * - Observable outcomes: source wiring keeps LazyPagingItems in the column, uses `pagedMemos.itemCount`
 *   for list size, reads `pagedMemos[index]` in the item content path, and passes a known total
 *   count into the LazyList scrollbar estimator.
 * - Red phase: Fails before the fix because the list renders only `itemSnapshotList.items`,
 *   so scrolling reaches the end of the loaded snapshot without ever requesting the next page. It
 *   also fails before the scrollbar fix because `snapshotMemos.size` is used as the scrollbar total.
 * - Excludes: Room SQL correctness, Compose lazy-list internals, and pixel-perfect scroll behavior.
 */
class MemoListPagingAppendContractTest {
    private val moduleRoot = resolveModuleRoot("app")
    private val source =
        moduleRoot.resolve("src/main/java/com/lomo/app/feature/main/PagedMemoListContent.kt").readText()
            .normalizeWhitespace()

    @Test
    fun `memo list reads LazyPagingItems indexes to trigger append loads`() {
        assertTrue(
            """
            MemoListContent must not render only the currently loaded snapshot. It should pass
            LazyPagingItems into the list column, size the LazyColumn from itemCount, and read
            pagedMemos[index] in the item lambda so Paging can load additional pages.
            """.trimIndent(),
            source.contains("pagedMemos: LazyPagingItems<MemoUiModel>") &&
                source.contains("MemoPagedListColumn( pagedMemos = pagedMemos,") &&
                source.contains("count = maxOf(visiblePagedMemos.size, pagedMemos.itemCount)") &&
                source.contains("pagedMemos[index]"),
        )
    }

    @Test
    fun `memo list scrollbar uses known total count instead of loaded snapshot count`() {
        assertTrue(
            """
            The main-list scrollbar must not derive its total from itemSnapshotList.items. Paging
            appends increase that loaded snapshot count, which makes the thumb jump upward even
            though the user's repository position did not move.
            """.trimIndent(),
            source.contains("knownTotalItemCount: Int") &&
                source.contains("totalItemsCountOverride = scrollbarItemCount") &&
                source.contains("scrollTargetItemsCountOverride = renderedItemCount") &&
                !source.contains("itemCount = snapshotMemos.size"),
        )
    }

    private fun String.normalizeWhitespace(): String = replace(Regex("\\s+"), " ").trim()

    private fun resolveModuleRoot(moduleName: String): File {
        val currentDirPath = System.getProperty("user.dir") ?: "."
        val currentDir = File(currentDirPath)
        val candidateRoots = listOf(currentDir, currentDir.resolve(moduleName))
        return checkNotNull(
            candidateRoots.firstOrNull { dir ->
                dir.name == moduleName && dir.resolve("build.gradle.kts").exists()
            },
        ) {
            "Failed to resolve $moduleName module root from $currentDirPath"
        }
    }
}
