package com.lomo.app.feature.memo

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: non-main memo-list delete animation wiring.
 * - Behavior focus: shared memo-card list surfaces and Trash must wire the same retained delete ids,
 *   settle callback, and viewport-entry compensation contract used by the main memo list so off-screen
 *   rows do not diverge from the main-page delete motion. Shared top-entry movement must use the
 *   one-shot compensation path rather than raw shared-offset translation, and bottom top-entry rows
 *   must start from above the viewport instead of becoming opaque inside the viewport.
 * - Observable outcomes: required source snippets across the shared list and its consuming screens.
 * - Red phase: Fails before the fix because Search/Tag/Gallery do not pass deleting ids into MemoCardList,
 *   MemoCardList does not retain deleting rows or apply viewport-entry compensation, and Trash keeps its
 *   own separate delete animation path without the main-page compensation contract. It also fails
 *   before the bottom-entry alignment fix when non-main surfaces do not share the viewport-filtered,
 *   negative top-entry one-shot compensation helper.
 * - Excludes: runtime Compose frame sampling, exact easing values, and ViewModel implementation details
 *   outside the delete-animation wiring surface.
 */
/*
 * Test Change Justification:
 * - Reason category: product bug regression boundary extension plus mechanical source split alignment.
 * - Old behavior/assertion being replaced: the previous contract only required the generic
 *   deleteViewportEntryCompensation modifier wiring and assumed the viewport placement policy lived
 *   in DeleteViewportEntryCompensation.kt.
 * - Why the old assertion is no longer correct: the raw shared-offset branch can reintroduce
 *   bottom-delete flicker on non-main memo lists even after the main list moves to one-shot
 *   placement compensation; the shared helper must now also lock viewport filtering and negative
 *   top-entry placement so rows do not flash into the viewport. The placement policy is now split
 *   into a smaller file, so the same contract must scan that file instead of only the old owner.
 * - Coverage preserved by: retained delete ids, settle callback wiring, viewport measurement,
 *   and shared modifier reuse remain required.
 * - Why this is not fitting the test to the implementation: the new snippet locks cross-screen
 *   parity for the same user-visible no-flicker delete behavior.
 */
/*
 * Test Change Justification:
 * - Reason category: architectural contract replacement.
 * - Old behavior/assertion being replaced: non-main memo delete surfaces were required to reuse
 *   app-local DeleteViewportEntryCompensation helpers directly.
 * - Why the old assertion is no longer correct: viewport-aware remove motion now lives in the
 *   shared ui-components LazyListMotion framework, which must be the only row-movement owner.
 * - Coverage preserved by: retained delete ids, settle callback wiring, viewport filtering,
 *   negative top-entry placement, and shared modifier adoption are still required.
 * - Why this is not fitting the test to the implementation: the updated snippets encode the
 *   requested root-cause fix: one shared framework across main, tag/gallery/search, and trash.
 */
class MemoCardListDeleteAnimationContractTest {
    private val appModuleRoot = resolveModuleRoot("app")
    private val repoRoot = checkNotNull(appModuleRoot.parentFile)
    private val sourceFiles =
        listOf(
            appModuleRoot.resolve("src/main/java/com/lomo/app/feature/memo/MemoCardListAnimation.kt"),
            appModuleRoot.resolve("src/main/java/com/lomo/app/feature/search/SearchScreen.kt"),
            appModuleRoot.resolve("src/main/java/com/lomo/app/feature/tag/TagFilterScreen.kt"),
            appModuleRoot.resolve("src/main/java/com/lomo/app/feature/gallery/GalleryScreen.kt"),
            appModuleRoot.resolve("src/main/java/com/lomo/app/feature/trash/TrashScreen.kt"),
            repoRoot.resolve("ui-components/src/main/java/com/lomo/ui/component/common/LazyListMotionState.kt"),
            repoRoot.resolve("ui-components/src/main/java/com/lomo/ui/component/common/LazyListMotionRemoveState.kt"),
            repoRoot.resolve("ui-components/src/main/java/com/lomo/ui/component/common/LazyListMotionRemovePlacement.kt"),
            repoRoot.resolve("ui-components/src/main/java/com/lomo/ui/component/common/LazyListMotionSnapshot.kt"),
        )

    @Test
    fun `non main delete surfaces keep main-page-aligned delete animation wiring`() {
        val content = sourceFiles.joinToString(separator = " ") { it.readText() }.normalizeWhitespace()

        assertTrue(
            """
            Non-main delete surfaces must route deleting ids and the settle callback into the shared memo-card
            list, and both the shared list and Trash must reuse the main-page viewport-entry compensation
            contract so delete motion stays aligned across screens.
            """.trimIndent(),
            REQUIRED_SNIPPETS.all(content::contains),
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
                "deletingMemoIds =",
                "onDeleteAnimationSettled =",
                "rememberRetainedVisibleItems(",
                "rememberLazyListMotionState(",
                ".lazyListMotionItem(",
                "viewModel::onDeleteAnimationSettled",
                "toLazyListMotionViewportSnapshot().viewportVisibleIds()",
                "remainingDistancePx.coerceAtLeast(viewportOvershootPx)",
                "initialOffsetPx = initialOffsetPx * candidate.direction.offsetSign",
            )
    }
}
