package com.lomo.app.feature.main

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lomo.domain.model.Memo
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/*
 * Test Contract:
 * - Unit under test: Main-screen staged new-memo insert animation on a real device.
 * - Behavior focus: when creating at the absolute top, the current first memo must move downward before the new
 *   memo becomes visibly opaque, and the old first memo must not jump upward into the hidden top region during
 *   the staged insert.
 * - Observable outcomes: sampled screen-Y positions for the old top memo, sampled alpha for the new memo view,
 *   final absolute-top list state, and final visible new memo.
 * - Red phase: Fails before the fix when the new memo reveal starts too early or when repeated top anchoring
 *   causes the previous first memo to jump upward and flash the hidden top viewport region.
 * - Excludes: repository persistence, Hilt wiring, exact easing curves, and full MainScreen integration.
 */
/*
 * Test Change Justification:
 * - Reason category: pure refactor preserved behavior but requires mechanical test reshaping.
 * - Old behavior/assertion being replaced: the harness recovered prepend animation by reading the real viewport
 *   top and issuing a compensating post-insert top pin before blank-space prep.
 * - Why the previous assertion is no longer correct: prepend should now recover the list to the absolute top
 *   before insertion, then begin the staged insert directly from the new first item. Keeping the old repin gate
 *   in the harness would test the obsolete workaround rather than the requested animation order.
 * - Coverage preserved by: the assertions still protect no upward flash, move-before-reveal ordering, and
 *   hidden-before-reveal alpha.
 * - Why this is not fitting the test to the implementation: the harness is being aligned to the requested motion
 *   sequence, not weakened to accept any behavior.
 */
@RunWith(AndroidJUnit4::class)
class MainScreenNewMemoAnimationDeviceTest {
    @Suppress("DEPRECATION")
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun prependAtTop_movesExistingCardsBeforeNewMemoReveal_andDoesNotJumpUpward() {
        val harness = AnimationHarness(mutableStateOf(memoUiModels(count = 24)))
        val newMemoText = "New memo staged reveal"

        setContent(harness = harness)
        waitForTextView("Memo 00")

        val initialTopY = readTextViewScreenTop("Memo 00")
        composeRule.mainClock.autoAdvance = false

        composeRule.runOnIdle {
            harness.coordinator.submit(newMemoText)
        }

        val samples = mutableListOf<AnimationSample>()
        repeat(24) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            samples +=
                AnimationSample(
                    oldTopY = readTextViewScreenTopOrNull("Memo 00"),
                    state = harness.animationState,
                )
        }
        composeRule.mainClock.autoAdvance = true

        assertTrue(
            "Expected the previous top memo to stay at or below its starting Y while the insert animation runs, but sampled $samples from initialY=$initialTopY.",
            samples.all { sample ->
                val oldTopY = sample.oldTopY ?: return@all true
                oldTopY >= initialTopY - POSITION_TOLERANCE_PX
            },
        )

        val firstVisibleRevealIndex =
            samples.indexOfFirst { sample ->
                sample.state.pendingRevealMemoId != null
            }
        assertTrue(
            "Expected the new memo animation to reach the reveal stage during the sampled window, but sampled $samples.",
            firstVisibleRevealIndex >= 0,
        )
        assertTrue(
            "Expected the previous top memo to move downward before the new memo reached reveal stage, but sampled $samples from initialY=$initialTopY.",
            samples
                .take(firstVisibleRevealIndex + 1)
                .any { sample ->
                    val oldTopY = sample.oldTopY ?: return@any false
                    oldTopY > initialTopY + POSITION_TOLERANCE_PX &&
                        (
                            sample.state.blankSpaceMemoId != null ||
                                sample.state.gapReadyMemoId != null
                        )
                },
        )

        waitForListAtAbsoluteTop(harness.listState)
        waitForTextView(newMemoText)
    }

    @Test
    fun prependAtTop_revealsOnlyAfterPreviousTopMemoSettlesIntoItsDisplacedGap() {
        val harness = AnimationHarness(mutableStateOf(memoUiModels(count = 24)))
        val newMemoText = "New memo reveal after settle"

        setContent(harness = harness)
        waitForTextView("Memo 00")

        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle {
            harness.coordinator.submit(newMemoText)
        }

        val samples = mutableListOf<VisibilitySample>()
        repeat(32) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            samples +=
                VisibilitySample(
                    oldTopY = readTextViewScreenTopOrNull("Memo 00"),
                    newMemoAlpha = readTextViewAlphaOrNull(newMemoText),
                )
        }

        composeRule.mainClock.autoAdvance = true
        waitForListAtAbsoluteTop(harness.listState)
        waitForTextView(newMemoText)
        val settledOldTopY = readTextViewScreenTop("Memo 00")

        val firstRevealIndex =
            samples.indexOfFirst { sample ->
                val alpha = sample.newMemoAlpha ?: return@indexOfFirst false
                alpha > ALPHA_TOLERANCE
            }
        assertTrue(
            "Expected the new memo to begin reveal during sampling, but sampled $samples.",
            firstRevealIndex >= 0,
        )

        val oldTopYAtReveal = samples[firstRevealIndex].oldTopY
        assertTrue(
            "Expected the previous top memo to settle into its displaced gap before reveal began, but reveal started at sample ${samples[firstRevealIndex]} while settledOldTopY=$settledOldTopY and samples=$samples.",
            oldTopYAtReveal != null && oldTopYAtReveal >= settledOldTopY - POSITION_TOLERANCE_PX,
        )

        val preRevealVisibleAlpha =
            samples
                .take(firstRevealIndex)
                .mapNotNull { it.newMemoAlpha }
        assertTrue(
            "Expected the new memo to stay effectively invisible before reveal, but sampled pre-reveal alpha values $preRevealVisibleAlpha from $samples.",
            preRevealVisibleAlpha.all { alpha -> alpha <= ALPHA_TOLERANCE },
        )
    }

    @Composable
    private fun AnimationHarnessContent(harness: AnimationHarness) {
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val animationSession = remember { NewMemoInsertAnimationSession() }
        val currentListTopMemoId = harness.memos.value.firstOrNull()?.memo?.id
        val latestTopMemoId = rememberUpdatedState(currentListTopMemoId)
        var nextNewMemoIndex by remember { mutableIntStateOf(0) }

        val coordinator =
            remember(listState, scope) {
                NewMemoCreationCoordinator<String>(
                    scope = scope,
                    isListAtAbsoluteTop = {
                        listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                    },
                    scrollListToAbsoluteTop = {
                        listState.scrollToItem(0)
                    },
                    createMemo = { content ->
                        animationSession.arm(previousTopMemoId = latestTopMemoId.value)
                        nextNewMemoIndex += 1
                        harness.memos.value =
                            (
                                listOf(
                                    memoUiModel(
                                        id = "new-$nextNewMemoIndex",
                                        content = content,
                                    ),
                                ) + harness.memos.value
                            ).toImmutableList()
                    },
                )
            }

        LaunchedEffect(
            currentListTopMemoId,
            animationSession.state.awaitingInsertedTopMemo,
            animationSession.state.blankSpaceMemoId,
            animationSession.state.previousTopMemoId,
            animationSession.state.gapReadyMemoId,
            listState.firstVisibleItemIndex,
            listState.firstVisibleItemScrollOffset,
        ) {
            val currentState = animationSession.state
            val isListPinnedAtTop =
                listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            when {
                currentState.awaitingInsertedTopMemo -> {
                    withFrameNanos { }
                    if (
                        isInsertedTopMemoReadyForSpaceStage(
                            state = currentState,
                            currentListTopMemoId = currentListTopMemoId,
                            isListPinnedAtTop = listState.firstVisibleItemIndex == 0 &&
                                listState.firstVisibleItemScrollOffset == 0,
                        )
                    ) {
                        animationSession.markInsertedTopMemoReady(insertedTopMemoId = currentListTopMemoId)
                    }
                }

                currentState.gapReadyMemoId != null -> {
                    withFrameNanos { }
                    if (animationSession.state.gapReadyMemoId == currentState.gapReadyMemoId) {
                        animationSession.markRevealReady(currentState.gapReadyMemoId)
                    }
                }
            }
        }

        SideEffect {
            harness.listState = listState
            harness.scope = scope
            harness.coordinator = coordinator
            harness.animationState = animationSession.state
        }

        MaterialTheme {
            MemoListContent(
                memos = harness.memos.value,
                deletingMemoIds = persistentSetOf(),
                collapsingMemoIds = persistentSetOf(),
                newMemoInsertAnimationState = animationSession.state,
                onNewMemoSpacePrepared = animationSession::markBlankSpacePrepared,
                onNewMemoRevealConsumed = animationSession::clearReveal,
                listState = listState,
                isRefreshing = false,
                onRefresh = {},
                onTodoClick = { _, _, _ -> },
                dateFormat = "yyyy-MM-dd",
                timeFormat = "HH:mm",
                onTagClick = {},
                onImageClick = {},
                onShowMemoMenu = {},
            )
        }
    }

    private fun setContent(harness: AnimationHarness) {
        composeRule.setContent {
            AnimationHarnessContent(harness = harness)
        }
    }

    private fun waitForListAtAbsoluteTop(
        listState: LazyListState,
        timeoutMillis: Long = 5_000,
    ) {
        composeRule.waitUntil(timeoutMillis) {
            var atTop = false
            composeRule.runOnUiThread {
                atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            }
            atTop
        }
    }

    private fun waitForTextView(
        expectedText: String,
        timeoutMillis: Long = 5_000,
    ): TextView {
        var textView: TextView? = null
        composeRule.waitUntil(timeoutMillis) {
            composeRule.runOnUiThread {
                textView = findTextView(composeRule.activity.findViewById(android.R.id.content), expectedText)
            }
            textView != null
        }
        return checkNotNull(textView)
    }

    private fun readTextViewScreenTop(expectedText: String): Int {
        val textView = waitForTextView(expectedText)
        val location = IntArray(2)
        composeRule.runOnUiThread {
            textView.getLocationOnScreen(location)
        }
        return location[1]
    }

    private fun readTextViewScreenTopOrNull(expectedText: String): Int? {
        val textView = findTextViewOnUiThread(expectedText) ?: return null
        val location = IntArray(2)
        composeRule.runOnUiThread {
            textView.getLocationOnScreen(location)
        }
        return location[1]
    }

    private fun readTextViewAlphaOrNull(expectedText: String): Float? {
        val textView = findTextViewOnUiThread(expectedText) ?: return null
        var alpha = 0f
        composeRule.runOnUiThread {
            alpha = textView.alpha
        }
        return alpha
    }

    private fun findTextViewOnUiThread(expectedText: String): TextView? {
        var textView: TextView? = null
        composeRule.runOnUiThread {
            textView = findTextView(composeRule.activity.findViewById(android.R.id.content), expectedText)
        }
        return textView
    }

    private fun findTextView(
        root: View,
        expectedText: String,
    ): TextView? {
        if (root is TextView && root.text?.toString()?.contains(expectedText) == true) {
            return root
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findTextView(root.getChildAt(index), expectedText)?.let { return it }
            }
        }
        return null
    }

    private fun memoUiModels(count: Int): ImmutableList<MemoUiModel> =
        List(count) { index ->
            memoUiModel(
                id = "memo-${index.toString().padStart(2, '0')}",
                content = "Memo ${index.toString().padStart(2, '0')}",
            )
        }.toImmutableList()

    private fun memoUiModel(
        id: String,
        content: String,
    ): MemoUiModel {
        val localDate = LocalDate.of(2026, 4, 5)
        return MemoUiModel(
            memo =
                Memo(
                    id = id,
                    timestamp =
                        localDate
                            .atStartOfDay(ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli(),
                    content = content,
                    rawContent = content,
                    dateKey = "2026_04_05",
                    localDate = localDate,
                ),
            processedContent = content,
            precomputedRenderPlan = null,
            tags = persistentListOf(),
        )
    }

    private data class AnimationSample(
        val oldTopY: Int?,
        val state: NewMemoInsertAnimationState,
    )

    private data class VisibilitySample(
        val oldTopY: Int?,
        val newMemoAlpha: Float?,
    )

    private class AnimationHarness(
        val memos: MutableState<ImmutableList<MemoUiModel>>,
    ) {
        lateinit var listState: LazyListState
        lateinit var scope: CoroutineScope
        lateinit var coordinator: NewMemoCreationCoordinator<String>
        var animationState: NewMemoInsertAnimationState = NewMemoInsertAnimationState()
    }

    private companion object {
        const val ALPHA_TOLERANCE = 0.05f
        const val POSITION_TOLERANCE_PX = 2
    }
}
