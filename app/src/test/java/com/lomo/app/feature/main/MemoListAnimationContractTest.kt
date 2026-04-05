package com.lomo.app.feature.main

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: MemoListContent animation contract
 * - Behavior focus: keep source-level animation constants and snippets that define insert and delete motion.
 * - Observable outcomes: required animation declarations remain present in MemoListContent.kt.
 * - Red phase: Fails before the fix because the delete path removes animateItem entirely when a row enters deleting state, instead of keeping a stable animateItem modifier and downgrading placement motion to snap().
 * - Excludes: runtime Compose rendering, timing interpolation internals, and unrelated list behavior.
 */
/*
 * Test Change Justification:
 * - Reason category: product contract changed.
 * - Old behavior/assertion being replaced: the previous insert-side contract accepted a generic row enter motion
 *   driven by `expandVertically`, and later revisions still required viewport-top recovery plus explicit top
 *   repinning before the staged insert could begin.
 * - Why the old assertion is no longer correct: the intended prepend behavior mirrors delete. Lower memos should
 *   first move down because a blank top slot opens, and only then should the new memo content fade into that
 *   already-visible slot. Once the list is already at the absolute top, the staged insert must begin directly
 *   from the new first item instead of waiting for a second viewport-top correction.
 * - Coverage preserved by: the contract still requires insertion-session gating, hidden-until-pinned top-row
 *   handling, deferred reveal timing, and stable alpha compositing for delete motion. It now additionally locks a
 *   dedicated insert-space stage so the list displacement completes before the new card content starts fading in,
 *   forbids post-insert repinning that can flash the invisible top viewport region, and requires placement spring
 *   motion to stay disabled while the staged insert is active so reveal cannot overlap with residual sibling
 *   settling.
 * - Why this is not fitting the test to the implementation: the changed assertion encodes the user-visible motion requirement rather than a private refactor detail.
 */
class MemoListAnimationContractTest {
    private val moduleRoot = resolveModuleRoot("app")
    private val sourceFiles =
        listOf(
            moduleRoot.resolve("src/main/java/com/lomo/app/feature/main/MemoListContent.kt"),
            moduleRoot.resolve("src/main/java/com/lomo/app/feature/main/MemoListItemRevealAlpha.kt"),
            moduleRoot.resolve("src/main/java/com/lomo/app/feature/main/MemoListItemInsertSpace.kt"),
            moduleRoot.resolve("src/main/java/com/lomo/app/feature/main/MainScreen.kt"),
            moduleRoot.resolve("src/main/java/com/lomo/app/feature/main/NewMemoInsertAnimationSession.kt"),
            moduleRoot.resolve("src/main/java/com/lomo/app/feature/main/TopViewportMemoId.kt"),
        )

    @Test
    fun `memo list keeps new memo enter animation`() {
        val content = sourceFiles.joinToString(separator = " ") { it.readText() }.normalizeWhitespace()

        assertTrue(
            """
            New memos must keep the staged insert-and-reveal animation across the main-screen list sources.
            Expected insertion-session state, a hidden-until-pinned top-row policy, delayed alpha reveal, and
            non-bouncy sibling placement spring in:
            ${sourceFiles.joinToString(separator = "\n") { it.path }}
            """.trimIndent(),
            NEW_MEMO_ENTER_ANIMATION_CONSTANTS.all(content::contains) &&
            NEW_MEMO_ENTER_ANIMATION_SNIPPETS.all(content::contains) &&
            NEW_MEMO_ENTER_ANIMATION_FORBIDDEN_SNIPPETS.none(content::contains),
        )
    }

    @Test
    fun `memo list keeps delete fade animation`() {
        val content = sourceFiles.joinToString(separator = " ") { it.readText() }.normalizeWhitespace()

        assertTrue(
            """
            Deleting memos must keep the fade-out animation in the main-screen list sources.
            Expected delete visual policy resolution, a stable animateItem modifier whose placementSpec
            downgrades to snap() while deleting, and stable graphicsLayer alpha application in:
            ${sourceFiles.joinToString(separator = "\n") { it.path }}
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
                "private const val MEMO_INSERT_SPACE_ANIMATION_DURATION_MILLIS = 220",
                "private const val MEMO_NEW_ITEM_REVEAL_DURATION_MILLIS = 300",
            )

        val NEW_MEMO_ENTER_ANIMATION_SNIPPETS =
            listOf(
                "val awaitingInsertedTopMemo: Boolean = false",
                "val blankSpaceMemoId: String? = null",
                "val gapReadyMemoId: String? = null",
                "val pendingRevealMemoId: String? = null",
                "val blocksPlacementSpring: Boolean",
                "currentListTopMemoId = currentListTopMemoId",
                "newMemoInsertAnimationState.awaitingInsertedTopMemo",
                "newMemoInsertAnimationState.blankSpaceMemoId == uiModel.memo.id",
                "newMemoInsertAnimationState.gapReadyMemoId == uiModel.memo.id",
                "newMemoInsertAnimationState.pendingRevealMemoId == uiModel.memo.id",
                "rememberMemoItemInsertAnimation(",
                "shouldHoldGapReadyMemoHidden = shouldHoldGapReadyMemoHidden",
                "shouldAnimateNewMemoSpace = shouldAnimateNewMemoSpace",
                "onNewMemoSpacePrepared = onNewMemoSpacePrepared",
                "spaceFraction = insertAnimation.spaceFraction",
                "clipToBounds()",
                "val animatedHeight = (placeable.height * spaceFraction).roundToInt()",
                "val animatedBottomSpacing = (bottomSpacingPx * spaceFraction).roundToInt()",
                "enter = EnterTransition.None",
                ".animateItem(",
                "fadeInSpec = null",
                "spaceFraction.animateTo(",
                "durationMillis = MEMO_INSERT_SPACE_ANIMATION_DURATION_MILLIS",
                "onNewMemoSpacePrepared(memoId)",
                "listState.scrollToItem(0)",
                "newMemoInsertAnimationSession.markInsertedTopMemoReady(",
                "newMemoInsertAnimationSession.markRevealReady(currentState.gapReadyMemoId)",
                "durationMillis = MEMO_NEW_ITEM_REVEAL_DURATION_MILLIS",
                "withFrameNanos { }",
                "fadeOutSpec = null",
                "placementSpec = if (deleteAnimationPolicy.animatePlacement && !newMemoInsertAnimationState.blocksPlacementSpring) { spring(",
                "stiffness = Spring.StiffnessLow",
                "dampingRatio = Spring.DampingRatioNoBouncy",
            )

        val NEW_MEMO_ENTER_ANIMATION_FORBIDDEN_SNIPPETS =
            listOf(
                "if (fadeInEnabled) { keyframes {",
                "MEMO_INSERT_FADE_DELAY_MILLIS",
                "durationMillis = MEMO_INSERT_ANIMATION_DURATION_MILLIS",
                "currentState.blankSpaceMemoId != null -> { listState.requestScrollToItem(0) }",
                "shouldRequestPostInsertTopPin(",
            )

        val DELETE_FADE_ANIMATION_CONSTANTS =
            listOf(
                "private const val MEMO_ITEM_ALPHA_THRESHOLD = 0.999f",
                "private const val MEMO_DELETE_ANIMATION_DURATION_MILLIS = 300",
            )

        val DELETE_FADE_ANIMATION_SNIPPETS =
            listOf(
                "resolveDeleteAnimationVisualPolicy(",
                ".memoListPlacementAnimation( lazyItemScope = this, deleteAnimationPolicy = deleteAnimationPolicy, newMemoInsertAnimationState = newMemoInsertAnimationState, )",
                "this@memoListPlacementAnimation.animateItem(",
                "placementSpec = if (deleteAnimationPolicy.animatePlacement && !newMemoInsertAnimationState.blocksPlacementSpring) { spring(",
                "stiffness = Spring.StiffnessLow",
                "dampingRatio = Spring.DampingRatioNoBouncy",
                "} else { snap() }",
                "animateFloatAsState(",
                "targetValue = if (isDeleting) { MEMO_ITEM_HIDDEN_ALPHA } else { MEMO_ITEM_VISIBLE_ALPHA }",
                "durationMillis = MEMO_DELETE_ANIMATION_DURATION_MILLIS",
                "label = \"DeleteAlpha\"",
                "keepStableAlphaLayer = deleteAnimationPolicy.keepStableAlphaLayer",
                "Modifier.memoVisibilityModifier(",
                "Modifier.graphicsLayer { this.alpha = alpha compositingStrategy = CompositingStrategy.ModulateAlpha }",
            )
    }
}
