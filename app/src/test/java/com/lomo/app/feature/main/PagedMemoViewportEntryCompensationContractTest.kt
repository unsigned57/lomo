package com.lomo.app.feature.main

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: active Paging memo-list delete-motion pipeline.
 * - Behavior focus: the Paging list must attach a dedicated viewport-aware one-shot compensation
 *   layer so rows that first become visible during delete collapse animate from their viewport
 *   overshoot rather than appearing immediately at the final settled position. The compensation
 *   must consume the remaining delete timeline rather than starting a separate spring; top-edge
 *   entries and bottom-edge entries must use a linear remaining-distance timeline so rows
 *   entering at different frames do not drift into each other. The layer must be orthogonal to
 *   animateItem placement spring (which already covers on-screen rows) so the two systems do not
 *   stack on the same row. LazyList prelayout rows that do not intersect the viewport must be
 *   excluded from the delete session's initial/previous/current visible-id sets so the first
 *   bottom top-entry row cannot skip compensation.
 * - Observable outcomes: required source snippets across the Paging list composable and the
 *   compensation helper implementation.
 * - Red phase: Fails before the fix because PagedMemoListContent only wires retained rows and
 *   animateItem placement; it does not measure delete-row geometry, track a delete session, or
 *   route per-row compensation payloads from compensationFor into the deleteViewportEntryCompensation
 *   Modifier. It also fails before the speed fix when the Modifier ignores compensation.durationMillis
 *   and when negative top-entry compensation uses the non-linear per-item easing path. It fails
 *   before the flicker fix when bottom top-entry rows bypass the one-shot placement map and use
 *   the raw shared-offset path after delayed reveal. It fails before the viewport-filter fix when
 *   the LazyList intake passes every visibleItemsInfo key directly into the session instead of
 *   routing through viewportVisibleIds.
 * - Excludes: runtime device frame sampling, exact easing curves, and Trash screen reuse.
 */
/*
 * Test Change Justification:
 * - Reason category: product bug regression boundary extension.
 * - Old behavior/assertion being replaced: the previous source contract required direct
 *   listState.layoutInfo.visibleItemsInfo access at the session sync callsite.
 * - Why the old assertion is no longer correct: the callsite now needs to route LazyList
 *   prelayout rows through DeleteViewportEntryVisibilitySnapshot.viewportVisibleIds so rows
 *   outside the viewport are not treated as initially visible.
 * - Coverage preserved by: the contract still requires retained list wiring, measured delete
 *   geometry, compensation consumption, LinearEasing, snapshotFlow viewport intake, session
 *   creation, and the viewport-intersection helper.
 * - Why this is not fitting the test to the implementation: the added snippets lock the
 *   user-visible no-flicker and no-catch-up behavior that the device regression exposes, including
 *   the first bottom top-entry row staying on the same compensation path as later rows.
 */
/*
 * Test Change Justification:
 * - Reason category: architectural contract replacement.
 * - Old behavior/assertion being replaced: the paging list was required to wire app-local
 *   DeleteViewportEntryCompensation directly.
 * - Why the old assertion is no longer correct: delete viewport-entry compensation now belongs
 *   to shared LazyListMotion so every LazyColumn surface gets the same remove/resize policy.
 * - Coverage preserved by: measured delete geometry, viewport filtering, LinearEasing,
 *   remaining-duration consumption, and session creation are still required in the shared owner.
 * - Why this is not fitting the test to the implementation: the new snippets protect the same
 *   no-flicker delete viewport behavior while enforcing the requested framework boundary.
 */
class PagedMemoViewportEntryCompensationContractTest {
    private val moduleRoot = resolveModuleRoot("app")
    private val repoRoot = checkNotNull(moduleRoot.parentFile)
    private val sourceFiles =
        listOf(
            moduleRoot.resolve("src/main/java/com/lomo/app/feature/main/PagedMemoListContent.kt"),
            repoRoot.resolve("ui-components/src/main/java/com/lomo/ui/component/common/LazyListMotionState.kt"),
            repoRoot.resolve("ui-components/src/main/java/com/lomo/ui/component/common/LazyListMotionRemoveState.kt"),
            repoRoot.resolve("ui-components/src/main/java/com/lomo/ui/component/common/LazyListMotionRemovePlacement.kt"),
            repoRoot.resolve("ui-components/src/main/java/com/lomo/ui/component/common/LazyListRemoveFrame.kt"),
            repoRoot.resolve("ui-components/src/main/java/com/lomo/ui/component/common/LazyListMotionSnapshot.kt"),
        )

    @Test
    fun `paging memo list keeps delete viewport entry compensation wiring`() {
        val content = sourceFiles.joinToString(separator = " ") { it.readText() }.normalizeWhitespace()

        assertTrue(
            """
            Paging delete motion must keep a dedicated viewport-aware one-shot compensation layer in:
            ${sourceFiles.joinToString(separator = "\n") { it.path }}
            so rows entering the viewport during delete collapse animate from their viewport
            overshoot, while on-screen rows are left to animateItem placement spring alone (no
            stacked time-based offset on top of placement).
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
                "rememberLazyListMotionState(",
                "listMotionState.onItemMeasured(",
                ".lazyListMotionItem(",
                "removeState.onItemMeasured(",
                "removeState.placementFor(itemId)",
                "removeState.clearPlacement(",
                "durationMillis = placement.durationMillis",
                "LinearEasing",
                "removedPlacement.initialOffsetPx < 0f",
                "snapshotFlow {",
                "toLazyListMotionViewportSnapshot().viewportVisibleIds()",
                "visibleItemsInfo",
                "item.bottomPx > viewportStartPx && item.offsetPx < viewportEndPx",
                "LazyListRemoveSession(",
            )
    }
}
