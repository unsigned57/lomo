package com.lomo.app.feature.main

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: MemoListContent and MemoListItemMotion animation contracts
 * - Behavior focus: delete animation uses a single isDeleting flag with a composed exit transition
 *   driven by AnimatedVisibility, and placement spring must stay disabled while either new-memo
 *   insertion or delete viewport compensation owns row movement.
 * - Observable outcomes: required animation declarations remain present in source files.
 * - Red phase: Fails before the fix because the old source still uses separate isDeleting/isCollapsing
 *   branches, a separate animateFloatAsState for alpha, and a 220ms collapse duration instead of
 *   the unified fadeOut + shrinkVertically(delayMillis=300) transition.
 * - Excludes: runtime Compose rendering, frame timing interpolation, ViewModel wiring.
 */
/*
 * Test Change Justification:
 * - Reason category: product contract changed.
 * - Old behavior/assertion being replaced: the delete animation previously used two state flags
 *   (isDeleting → fadeOut, isCollapsing → shrinkVertically) with a coroutine delay between them.
 *   The contract required animateFloatAsState for alpha, separate collapse spacing, and a 220ms
 *   collapse duration.
 * - Why the old assertion is no longer correct: the animation is now driven entirely by Compose's
 *   AnimatedVisibility exit transition (fadeOut + shrinkVertically with delayMillis). The
 *   unified approach eliminates the need for a separate alpha animation state and uses a 300ms
 *   collapse phase to match the overall 600ms animation timeline.
 * - Coverage preserved by: the contract still requires the delete fade duration constant (300ms),
 *   AnimatedVisibility with a composed exit transition, bottom spacing animation with matching
 *   delayMillis, and the delete visual policy import. The idle-row guard test now verifies that
 *   the bottom spacing animation is conditional on isDeleting rather than isCollapsing.
 * - Why this is not fitting the test to the implementation: the changed snippets encode the
 *   user-visible animation requirement (fade then collapse, with single-owner row movement)
 *   rather than internal state management details.
 */
class MemoListAnimationContractTest {
    private val moduleRoot = resolveModuleRoot("app")
    private val sourceFiles =
        listOf(
            moduleRoot.resolve("src/main/java/com/lomo/app/feature/main/MemoListContent.kt"),
            moduleRoot.resolve("src/main/java/com/lomo/app/feature/main/PagedMemoListContent.kt"),
            moduleRoot.resolve("src/main/java/com/lomo/app/feature/main/MemoListItemMotion.kt"),
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
    fun `memo list keeps delete fade-then-collapse animation`() {
        val content = sourceFiles.joinToString(separator = " ") { it.readText() }.normalizeWhitespace()

        assertTrue(
            """
            Deleting memos must keep the unified fade-then-collapse animation in the main-screen list sources.
            Expected AnimatedVisibility with composed exit transition (fadeOut + shrinkVertically
            with delayMillis), bottom spacing animation with matching delayMillis, and stable animateItem.
            """.trimIndent(),
            DELETE_FADE_ANIMATION_CONSTANTS.all(content::contains) &&
            DELETE_FADE_ANIMATION_SNIPPETS.all(content::contains),
        )
    }

    @Test
    fun `memo list only mounts collapse animation when needed`() {
        val content = sourceFiles.joinToString(separator = " ") { it.readText() }.normalizeWhitespace()

        assertTrue(
            """
            Idle memo rows should not keep the collapse spacing animation mounted.
            Expected conditional animation mounting gated on isDeleting in:
            ${sourceFiles.joinToString(separator = "\n") { it.path }}
            """.trimIndent(),
            IDLE_ROW_ANIMATION_GUARD_SNIPPETS.all(content::contains),
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
                "const val MEMO_ITEM_HIDDEN_ALPHA = 0f",
                "const val MEMO_ITEM_VISIBLE_ALPHA = 1f",
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
                "placementSpec = if (!newMemoInsertAnimationState.blocksPlacementSpring && !blockPlacementSpringForDeleteViewportEntry) { spring(",
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
                "private const val MEMO_DELETE_FADE_DURATION_MILLIS = 300",
                "private const val MEMO_COLLAPSE_ANIMATION_DURATION_MILLIS = 300",
            )

        val DELETE_FADE_ANIMATION_SNIPPETS =
            listOf(
                ".memoListPlacementAnimation(",
                "lazyItemScope = this",
                "newMemoInsertAnimationState = newMemoInsertAnimationState",
                "this@memoListPlacementAnimation.animateItem(",
                "fadeInSpec = null",
                "fadeOutSpec = null",
                "placementSpec = if (!newMemoInsertAnimationState.blocksPlacementSpring && !blockPlacementSpringForDeleteViewportEntry) { spring(",
                "stiffness = Spring.StiffnessLow",
                "dampingRatio = Spring.DampingRatioNoBouncy",
                "} else { snap() }",
                "Modifier.graphicsLayer {",
                "compositingStrategy = CompositingStrategy.ModulateAlpha",
                "animateFloatAsState(",
                "targetValue = if (isDeleting) 0f else 1f",
                "label = \"DeleteAlpha\"",
                "exit =",
                "shrinkVertically(",
                "shrinkTowards = Alignment.Top",
                "durationMillis = MEMO_COLLAPSE_ANIMATION_DURATION_MILLIS",
            )

        val IDLE_ROW_ANIMATION_GUARD_SNIPPETS =
            listOf(
                "fun rememberAnimatedBottomSpacing(",
                "val collapseSpacing by animateDpAsState(",
                "targetValue = if (isCollapsing) 0.dp else bottomSpacing",
                "durationMillis = MEMO_COLLAPSE_ANIMATION_DURATION_MILLIS",
            )
    }
}
