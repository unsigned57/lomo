package com.lomo.app.feature.main

/**
 * Behavior Contract:
 * - Unit under test: MemoListContent, LazyColumn, LazyPagingItems
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: Maintain viewport stability during deletion of items at deep scroll positions in a placeholder-enabled paged list.
 *
 * Scenarios:
 * - Given standard scroll position, when an item is deleted, then the exit animation plays and the remaining items shift up gracefully.
 * - Given a deep scroll position (around index 70) with unloaded pages, when an item is deleted, then the absolute index space is owned by the paging placeholder mechanism, and the viewport anchor remains stable without drifting or jumping.
 *
 * Observable outcomes:
 * - First visible item key and scroll offset remain stable after item deletion at deep scroll positions.
 * - Exit animation completes, and database list structure matches the rendered screen list.
 *
 * TDD proof:
 * - Fails prior to this refactoring because deleting an item at index 70 in a paged list without custom stabilizers caused viewport anchor drifting or scrolling jump.
 *
 * Excludes:
 * - DB sync network transport, background task scheduling.
 * 
 * Test Change Justification:
 * - Reason category: Migration
 * - Old behavior/assertion being replaced: JUnit4 assertions
 * - Why old assertion is no longer correct: Transitioning to Kotest
 * - Coverage preserved by: Kotest functional matching
 * - Why this is not fitting the test to the implementation: Syntax translation
 */


import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.paging.PagingData
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.domain.model.Memo
import com.lomo.ui.benchmark.BenchmarkAnchorConfig
import com.lomo.ui.benchmark.LocalBenchmarkAnchorConfig
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/*
 * Behavior Contract:
 * - Unit under test: MainScreen list prepend visibility, same-id edit remeasure, and delete-motion regression
 *   behavior on a real device.
 * - Owning layer: app
 * - Priority tier: P2
 * - Capability: verify prepend list visibility, same-id edit remeasure, and delete-motion regression behavior on device.
 *
 * Scenarios:
 * - Given prepend while at top, when new memo is submitted, then keep new card in visible viewport.
 * - Given prepend while away from top, when new memo is submitted, then scroll back and keep new card visible.
 * - Given edited memo in place, when height is modified, then remeasure height without manual scroll.
 * - Given middle memo deleted, when collapse begins, then move following memo upward without rebound.
 * - Given tall top memo deleted, when collapse occurs, then bring next previously hidden memo into view without jump.
 *
 * Observable outcomes:
 * - correct layout measurements, stable spacing between entering memos, Y position changes.
 *
 * TDD proof:
 * - Compilation failure on Kotest transition - test-only migration; no production change.
 *
 * Excludes:
 * - Hilt wiring, repository persistence, detailed easing-curve fidelity, and full app navigation.
 */
@org.junit.runner.RunWith(AndroidJUnit4::class)
class MainScreenListRegressionTest {
    @Suppress("DEPRECATION")
    @get:org.junit.Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @org.junit.Test
    fun prependWhileAlreadyAtTop_keepsTheNewMemoInTheVisibleViewport() {
        val harness = NewMemoHarness(mutableStateOf(memoUiModels(count = 24)))
        val newMemoText = "New memo at top viewport"

        setNewMemoHarnessContent(harness = harness)
        waitForTextView("Memo 00")

        composeRule.runOnIdle {
            harness.coordinator.submit(newMemoText)
        }

        waitForListAtAbsoluteTop(harness.listState)
        waitForTextView(newMemoText)
    }

    @org.junit.Test
    fun prependWhileAwayFromTop_scrollsBackAndKeepsTheNewMemoVisible() {
        val harness = NewMemoHarness(mutableStateOf(memoUiModels(count = 28)))
        val newMemoText = "New memo after returning to top"

        setNewMemoHarnessContent(harness = harness)
        waitForTextView("Memo 00")

        composeRule.runOnIdle {
            harness.scope.launch {
                harness.listState.scrollToItem(18)
            }
        }
        composeRule.waitUntil(5_000) {
            var isAwayFromTop = false
            composeRule.runOnUiThread {
                isAwayFromTop = harness.listState.firstVisibleItemIndex >= 18
            }
            isAwayFromTop
        }

        composeRule.runOnIdle {
            harness.coordinator.submit(newMemoText)
        }

        waitForListAtAbsoluteTop(harness.listState)
        waitForTextView(newMemoText)
    }

    @org.junit.Test
    fun editingMemoInPlace_remeasuresHeightWithoutManualScroll() {
        val editedMemoId = "memo-00"
        val followingMemoId = "memo-01"
        val harness =
            EditHarness(
                memos =
                    mutableStateOf(
                        listOf(
                            memoUiModel(
                                id = editedMemoId,
                                content = "Short memo for edit regression.",
                            ),
                            memoUiModel(
                                id = followingMemoId,
                                content = "Neighbor memo should stay directly below the edited one.",
                            ),
                            memoUiModel(
                                id = "memo-02",
                                content = "Trailing memo keeps the list dense enough for a realistic viewport.",
                            ),
                        ).toImmutableList(),
                    ),
            )

        setEditHarnessContent(harness = harness)
        waitForMemoCard(editedMemoId)
        waitForMemoCard(followingMemoId)
        val initialEditedHeight = readMemoCardBoundsInRoot(editedMemoId).height

        composeRule.runOnIdle {
            harness.updateMemo(
                id = editedMemoId,
                content = LONG_EDITED_MEMO_CONTENT,
            )
        }
        composeRule.waitForIdle()
        val expandedEditedHeight = readMemoCardBoundsInRoot(editedMemoId).height
        org.junit.Assert.assertTrue(
            "Expected the edited memo to become substantially taller after the long-content update, but height only changed from $initialEditedHeight to $expandedEditedHeight.",
            expandedEditedHeight >= initialEditedHeight + MIN_HEIGHT_DELTA_PX,
        )
        assertMemoCardsStaySeparated(
            editedMemoId = editedMemoId,
            followingMemoId = followingMemoId,
        )

        composeRule.runOnIdle {
            harness.updateMemo(
                id = editedMemoId,
                content = SHORT_EDITED_MEMO_CONTENT,
            )
        }
        composeRule.waitForIdle()
        val shrunkEditedHeight = readMemoCardBoundsInRoot(editedMemoId).height
        org.junit.Assert.assertTrue(
            "Expected the edited memo to shrink again after the short-content update, but height only changed from $expandedEditedHeight to $shrunkEditedHeight.",
            shrunkEditedHeight <= expandedEditedHeight - MIN_HEIGHT_DELTA_PX,
        )
        assertMemoCardsStaySeparated(
            editedMemoId = editedMemoId,
            followingMemoId = followingMemoId,
        )
    }

    @org.junit.Test
    fun deletingAMiddleMemo_movesTheFollowingMemoUpwardWithoutRebound() {
        val deleteTargetId = "memo-01"
        val followingMemoText = "Memo 02"
        val harness = DeleteHarness(mutableStateOf(memoUiModels(count = 6)))

        setDeleteHarnessContent(harness = harness)
        waitForTextView(followingMemoText)

        val beforeDeleteY = readTextViewScreenTop(followingMemoText)

        withManualAnimationClock {
            composeRule.runOnIdle {
                harness.startDelete(deleteTargetId)
            }

            advanceAnimationClockBy(180)
            val midFadeY = readTextViewScreenTop(followingMemoText)
            org.junit.Assert.assertTrue(
                "Expected the following memo to hold position during delete fade, but Y moved from $beforeDeleteY to $midFadeY.",
                kotlin.math.abs(midFadeY - beforeDeleteY) <= POSITION_TOLERANCE_PX,
            )

            composeRule.runOnIdle {
                harness.commitCollapsedRemoval(deleteTargetId)
            }

            val collapseSamples = mutableListOf<Int>()
            repeat(10) {
                advanceAnimationClockBy(50)
                collapseSamples += readTextViewScreenTop(followingMemoText)
            }

            org.junit.Assert.assertTrue(
                "Expected the following memo to move upward after the deleted row starts collapsing, but sampled Y positions were $collapseSamples.",
                collapseSamples.any { sampleY -> sampleY < beforeDeleteY - POSITION_TOLERANCE_PX },
            )

            var sawUpwardMotion = false
            var previousY = collapseSamples.first()
            collapseSamples.drop(1).forEach { currentY ->
                if (currentY < previousY - POSITION_TOLERANCE_PX) {
                    sawUpwardMotion = true
                }
                if (sawUpwardMotion) {
                    org.junit.Assert.assertTrue(
                        "Expected the following memo to keep moving upward or stay settled once collapse started, but sampled Y positions were $collapseSamples.",
                        currentY <= previousY + POSITION_TOLERANCE_PX,
                    )
                }
                previousY = currentY
            }
        }
    }

    @org.junit.Test
    fun deletingATallTopMemo_bringsTheNextPreviouslyHiddenMemoIntoViewWithoutJump() {
        val deleteTargetId = "memo-tall-delete"
        val enteringMemoId = "memo-entering"
        val enteringMemoText = "Memo enters from below during collapse"
        val harness =
            DeleteHarness(
                mutableStateOf(
                    listOf(
                        memoUiModel(
                            id = deleteTargetId,
                            content = VERY_LONG_DELETE_TARGET_CONTENT,
                        ),
                        memoUiModel(
                            id = enteringMemoId,
                            content = enteringMemoText,
                        ),
                        memoUiModel(
                            id = "memo-tail-1",
                            content = "Tail memo 1",
                        ),
                        memoUiModel(
                            id = "memo-tail-2",
                            content = "Tail memo 2",
                        ),
                    ).toImmutableList(),
                ),
            )

        setDeleteHarnessContent(harness)
        waitForTextView("Tall deleting memo")
        withManualAnimationClock {
            org.junit.Assert.assertTrue(
                "Expected the next memo to start below the viewport before delete, but it was already visible.",
                !isMemoVisibleInLazyViewport(harness.listState, enteringMemoId),
            )

            composeRule.runOnIdle {
                harness.startDelete(deleteTargetId)
            }

            advanceAnimationClockBy(180)
            org.junit.Assert.assertTrue(
                "Expected the next memo to stay outside the viewport during the delete fade, but it became visible too early.",
                !isMemoVisibleInLazyViewport(harness.listState, enteringMemoId),
            )

            composeRule.runOnIdle {
                harness.commitCollapsedRemoval(deleteTargetId)
            }

            waitForMemoVisibleInLazyViewport(harness.listState, enteringMemoId)
            val entrySamples = mutableListOf<Int>()
            repeat(8) {
                advanceAnimationClockBy(50)
                entrySamples += readMemoCardTopInRoot(enteringMemoId)
            }

            val firstVisibleY = entrySamples.first()
            val finalVisibleY = entrySamples.last()
            org.junit.Assert.assertTrue(
                "Expected the previously hidden memo to enter from below and keep moving upward, but sampled Y positions were $entrySamples.",
                firstVisibleY > finalVisibleY + POSITION_TOLERANCE_PX,
            )

            var previousY = firstVisibleY
            entrySamples.drop(1).forEach { currentY ->
                org.junit.Assert.assertTrue(
                    "Expected the entering memo to keep moving upward or stay settled once visible, but sampled Y positions were $entrySamples.",
                    currentY <= previousY + POSITION_TOLERANCE_PX,
                )
                previousY = currentY
            }
        }
    }

    @org.junit.Test
    fun deletingATallTopMemo_inPagedList_bringsTheNextPreviouslyHiddenMemoIntoViewWithoutJump() {
        val deleteTargetId = "memo-tall-delete"
        val enteringMemoId = "memo-entering"
        val enteringMemoText = "Memo enters from below during collapse"
        val harness =
            PagedDeleteHarness(
                initialMemos =
                    listOf(
                        memoUiModel(
                            id = deleteTargetId,
                            content = VERY_LONG_DELETE_TARGET_CONTENT,
                        ),
                        memoUiModel(
                            id = enteringMemoId,
                            content = enteringMemoText,
                        ),
                        memoUiModel(
                            id = "memo-tail-1",
                            content = "Tail memo 1",
                        ),
                        memoUiModel(
                            id = "memo-tail-2",
                            content = "Tail memo 2",
                        ),
                    ).toImmutableList(),
            )

        setPagedDeleteHarnessContent(harness)
        waitForTextView("Tall deleting memo")
        withManualAnimationClock {
            org.junit.Assert.assertTrue(
                "Expected the next memo to start below the viewport before delete, but it was already visible.",
                !isMemoVisibleInLazyViewport(harness.listState, enteringMemoId),
            )

            advanceAnimationClockBy(180)
            org.junit.Assert.assertTrue(
                "Expected the next memo to remain outside the viewport before delete starts, but the scenario settled into visibility on its own.",
                !isMemoVisibleInLazyViewport(harness.listState, enteringMemoId),
            )

            composeRule.runOnIdle {
                harness.startDelete(deleteTargetId)
            }

            advanceAnimationClockBy(180)
            org.junit.Assert.assertTrue(
                "Expected the next memo to stay outside the viewport during the delete fade, but it became visible too early.",
                !isMemoVisibleInLazyViewport(harness.listState, enteringMemoId),
            )

            composeRule.runOnIdle {
                harness.commitCollapsedRemoval(deleteTargetId)
            }

            waitForMemoVisibleInLazyViewport(harness.listState, enteringMemoId)
            val entrySamples = mutableListOf<Int>()
            repeat(8) {
                advanceAnimationClockBy(50)
                entrySamples += readMemoCardTopInRoot(enteringMemoId)
            }

            val firstVisibleY = entrySamples.first()
            val finalVisibleY = entrySamples.last()
            org.junit.Assert.assertTrue(
                "Expected the previously hidden memo to enter from below and keep moving upward in the real Paging list, but sampled Y positions were $entrySamples.",
                firstVisibleY > finalVisibleY + POSITION_TOLERANCE_PX,
            )

            var previousY = firstVisibleY
            entrySamples.drop(1).forEach { currentY ->
                org.junit.Assert.assertTrue(
                    "Expected the entering memo to keep moving upward or stay settled once visible in the real Paging list, but sampled Y positions were $entrySamples.",
                    currentY <= previousY + POSITION_TOLERANCE_PX,
                )
                previousY = currentY
            }
        }
    }

    @org.junit.Test
    fun deletingATallTopMemo_inPagedList_keepsMultipleEnteringMemosAtConfiguredSpacing() {
        val deleteTargetId = "memo-tall-delete"
        val firstEnteringId = "memo-00"
        val secondEnteringId = "memo-01"
        val firstEnteringText = "Memo 00"
        val secondEnteringText = "Memo 01"
        val harness =
            PagedDeleteHarness(
                initialMemos =
                    (
                        listOf(
                            memoUiModel(
                                id = deleteTargetId,
                                content = VERY_LONG_DELETE_TARGET_CONTENT,
                            ),
                        ) + memoUiModels(count = 20)
                    ).toImmutableList(),
            )

        setPagedDeleteHarnessContent(harness)
        waitForTextView("Tall deleting memo")

        withManualAnimationClock {
            org.junit.Assert.assertTrue(
                "Expected the second memo below the tall top delete target to start outside the viewport before delete.",
                !isMemoVisibleInLazyViewport(harness.listState, secondEnteringId),
            )

            composeRule.runOnIdle {
                harness.startDelete(deleteTargetId)
            }

            advanceAnimationClockBy(180)
            org.junit.Assert.assertTrue(
                "Expected the later entering memo to stay outside the viewport during the delete fade.",
                !isMemoVisibleInLazyViewport(harness.listState, secondEnteringId),
            )

            composeRule.runOnIdle {
                harness.commitCollapsedRemoval(deleteTargetId)
            }

            waitForMemoVisibleInLazyViewport(harness.listState, firstEnteringId)
            waitForMemoVisibleInLazyViewport(harness.listState, secondEnteringId)

            var bothEnteringTextsVisible = false
            repeat(20) {
                advanceAnimationClockBy(50)
                val firstAlpha = readVisibleTextViewAlphaOrNull(firstEnteringText)
                val secondAlpha = readVisibleTextViewAlphaOrNull(secondEnteringText)
                if (
                    firstAlpha != null &&
                    firstAlpha > ALPHA_TOLERANCE &&
                    secondAlpha != null &&
                    secondAlpha > ALPHA_TOLERANCE
                ) {
                    bothEnteringTextsVisible = true
                    return@repeat
                }
            }
            org.junit.Assert.assertTrue(
                "Expected both entering memos to become visibly opaque during the sampled window, but firstAlpha=${readVisibleTextViewAlphaOrNull(firstEnteringText)} secondAlpha=${readVisibleTextViewAlphaOrNull(secondEnteringText)}.",
                bothEnteringTextsVisible,
            )

            val gapSamples = mutableListOf<Float>()
            val firstTopSamples = mutableListOf<Float>()
            val secondTopSamples = mutableListOf<Float>()
            repeat(8) {
                advanceAnimationClockBy(50)
                val firstBounds = readMemoCardBoundsInRoot(firstEnteringId)
                val secondBounds = readMemoCardBoundsInRoot(secondEnteringId)
                firstTopSamples += firstBounds.top
                secondTopSamples += secondBounds.top
                gapSamples += secondBounds.top - firstBounds.bottom
            }

            val expectedGapPx = expectedMemoCardSpacingPx()
            val firstUpwardSteps =
                firstTopSamples.zipWithNext { previous, current ->
                    previous - current
                }
            val secondUpwardSteps =
                secondTopSamples.zipWithNext { previous, current ->
                    previous - current
                }
            org.junit.Assert.assertTrue(
                "Expected multiple memos entering after a tall top delete to keep at least the configured spacing instead of catching up into the first entrant, but sampled gaps were $gapSamples with firstTopSamples=$firstTopSamples secondTopSamples=$secondTopSamples expectedGapPx=$expectedGapPx.",
                gapSamples.all { gap -> gap >= expectedGapPx - CARD_GAP_TOLERANCE_PX },
            )
            org.junit.Assert.assertTrue(
                "Expected the later entering memo not to move upward faster than the first entrant once both were visible, but firstUpwardSteps=$firstUpwardSteps secondUpwardSteps=$secondUpwardSteps firstTopSamples=$firstTopSamples secondTopSamples=$secondTopSamples.",
                firstUpwardSteps.zip(secondUpwardSteps).all { (firstStep, secondStep) ->
                    secondStep <= firstStep + POSITION_TOLERANCE_PX
                },
            )
        }
    }

    @org.junit.Test
    fun deletingATallTopMemo_inPagedList_settlesEnteringMemoAtSteadyStatePosition() {
        val deleteTargetId = "memo-tall-delete"
        val enteringMemoId = "memo-entering"
        val enteringMemoText = "Memo enters from below during collapse"
        val steadyStateHarness =
            PagedDeleteHarness(
                initialMemos =
                    listOf(
                        memoUiModel(
                            id = enteringMemoId,
                            content = enteringMemoText,
                        ),
                        memoUiModel(
                            id = "memo-tail-1",
                            content = "Tail memo 1",
                        ),
                        memoUiModel(
                            id = "memo-tail-2",
                            content = "Tail memo 2",
                        ),
                    ).toImmutableList(),
            )
        val activeHarness =
            mutableStateOf(
                SwitchablePagedDeleteHarness(
                    scenarioKey = "steady-state",
                    harness = steadyStateHarness,
                ),
            )
        val lateSettleTolerancePx = expectedMemoCardSpacingPx() + POSITION_TOLERANCE_PX

        setSwitchablePagedDeleteHarnessContent(activeHarness)
        waitForMemoCard(enteringMemoId)
        val steadyStateTop = readMemoCardTopInRoot(enteringMemoId)

        val animatedHarness =
            PagedDeleteHarness(
                initialMemos =
                    listOf(
                        memoUiModel(
                            id = deleteTargetId,
                            content = VERY_LONG_DELETE_TARGET_CONTENT,
                        ),
                        memoUiModel(
                            id = enteringMemoId,
                            content = enteringMemoText,
                        ),
                        memoUiModel(
                            id = "memo-tail-1",
                            content = "Tail memo 1",
                        ),
                        memoUiModel(
                            id = "memo-tail-2",
                            content = "Tail memo 2",
                        ),
                    ).toImmutableList(),
            )

        composeRule.runOnIdle {
            activeHarness.value =
                SwitchablePagedDeleteHarness(
                    scenarioKey = "animated-delete",
                    harness = animatedHarness,
                )
        }
        waitForTextView("Tall deleting memo")
        withManualAnimationClock {
            composeRule.runOnIdle {
                animatedHarness.startDelete(deleteTargetId)
            }

            advanceAnimationClockBy(180)
            composeRule.runOnIdle {
                animatedHarness.commitCollapsedRemoval(deleteTargetId)
            }

            waitForMemoVisibleInLazyViewport(animatedHarness.listState, enteringMemoId)
            advanceAnimationClockBy(600)
            val lateSettleTop = readMemoCardTopInRoot(enteringMemoId)
            org.junit.Assert.assertTrue(
                "Expected the entering memo to close almost all of the remaining gap shortly after entering the viewport, but steadyStateTop=$steadyStateTop, lateSettleTop=$lateSettleTop, and tolerance=$lateSettleTolerancePx.",
                kotlin.math.abs(lateSettleTop - steadyStateTop) <= lateSettleTolerancePx,
            )
            advanceAnimationClockBy(1_200)
            val settledTop = readMemoCardTopInRoot(enteringMemoId)
            org.junit.Assert.assertTrue(
                "Expected the entering memo to settle to the same final slot as the equivalent steady-state list, but steadyStateTop=$steadyStateTop and settledTop=$settledTop.",
                kotlin.math.abs(settledTop - steadyStateTop) <= POSITION_TOLERANCE_PX,
            )
        }
    }

    @org.junit.Test
    fun deletingATallBottomMemo_inPagedList_bringsMultiplePreviouslyHiddenMemosFromAboveWithoutCatchUp() {
        val deleteTargetId = "memo-tall-delete"
        val firstEnteringId = "memo-18"
        val secondEnteringId = "memo-19"
        val harness =
            PagedDeleteHarness(
                initialMemos =
                    (
                        memoUiModels(count = 20) +
                            memoUiModel(
                                id = deleteTargetId,
                                content = VERY_LONG_DELETE_TARGET_CONTENT,
                            )
                    ).toImmutableList(),
            )

        setPagedDeleteHarnessContent(harness)
        waitForTextView("Memo 00")
        composeRule.runOnIdle {
            harness.scope.launch {
                harness.listState.scrollToItem(harness.itemCount - 1, Int.MAX_VALUE)
            }
        }
        waitForTextView("Tall deleting memo")
        composeRule.waitUntil(5_000) {
            var atListEnd = false
            composeRule.runOnUiThread {
                atListEnd =
                    harness.listState.layoutInfo.visibleItemsInfo
                        .lastOrNull()
                        ?.key == deleteTargetId
            }
            atListEnd
        }

        withManualAnimationClock {
            org.junit.Assert.assertTrue(
                "Expected the memo above the bottom delete target to start outside the viewport before delete.",
                !isMemoVisibleInLazyViewport(harness.listState, secondEnteringId),
            )

            composeRule.runOnIdle {
                harness.startDelete(deleteTargetId)
            }

            advanceAnimationClockBy(180)
            org.junit.Assert.assertTrue(
                "Expected top-entry memos to stay outside the viewport during delete fade.",
                !isMemoVisibleInLazyViewport(harness.listState, secondEnteringId),
            )

            composeRule.runOnIdle {
                harness.commitCollapsedRemoval(deleteTargetId)
            }

            waitForMemoVisibleInLazyViewport(harness.listState, firstEnteringId)
            waitForMemoVisibleInLazyViewport(harness.listState, secondEnteringId)

            val firstTopSamples = mutableListOf<Int>()
            val secondTopSamples = mutableListOf<Int>()
            val gapSamples = mutableListOf<Float>()
            val layoutGapSamples = mutableListOf<Int?>()
            repeat(10) {
                advanceAnimationClockBy(50)
                val firstBounds = readMemoCardBoundsInRoot(firstEnteringId)
                val secondBounds = readMemoCardBoundsInRoot(secondEnteringId)
                firstTopSamples += firstBounds.top.toInt()
                secondTopSamples += secondBounds.top.toInt()
                gapSamples += secondBounds.top - firstBounds.bottom
                layoutGapSamples += readLazyLayoutGap(harness.listState, firstEnteringId, secondEnteringId)
            }

            org.junit.Assert.assertTrue(
                "Expected adjacent top-entry memos to keep their spacing instead of catching up into each other, but sampled gaps were $gapSamples, firstTopSamples=$firstTopSamples, secondTopSamples=$secondTopSamples, layoutGapSamples=$layoutGapSamples.",
                gapSamples.all { gap -> gap >= -CARD_GAP_TOLERANCE_PX },
            )
        }
    }

    @org.junit.Test
    fun deletingATallBottomMemo_inPagedList_keepsDeletedMemoGone_andEnteringRowsOpaqueOnceVisible() {
        val deleteTargetId = "memo-tall-delete"
        val firstEnteringId = "memo-18"
        val secondEnteringId = "memo-19"
        val firstEnteringText = "Memo 18"
        val secondEnteringText = "Memo 19"
        val harness =
            PagedDeleteHarness(
                initialMemos =
                    (
                        memoUiModels(count = 20) +
                            memoUiModel(
                                id = deleteTargetId,
                                content = VERY_LONG_DELETE_TARGET_CONTENT,
                            )
                    ).toImmutableList(),
            )

        setPagedDeleteHarnessContent(harness)
        waitForTextView("Memo 00")
        composeRule.runOnIdle {
            harness.scope.launch {
                harness.listState.scrollToItem(harness.itemCount - 1, Int.MAX_VALUE)
            }
        }
        waitForTextView("Tall deleting memo")
        composeRule.waitUntil(5_000) {
            var atListEnd = false
            composeRule.runOnUiThread {
                atListEnd =
                    harness.listState.layoutInfo.visibleItemsInfo
                        .lastOrNull()
                        ?.key == deleteTargetId
            }
            atListEnd
        }

        withManualAnimationClock {
            composeRule.runOnIdle {
                harness.startDelete(deleteTargetId)
            }

            advanceAnimationClockBy(180)
            val midFadeDeleteAlpha = readVisibleTextViewAlphaOrNull("Tall deleting memo")
            org.junit.Assert.assertTrue(
                "Expected the tall bottom delete target to remain materially visible during the fade stage, but midFadeDeleteAlpha=$midFadeDeleteAlpha.",
                midFadeDeleteAlpha != null && midFadeDeleteAlpha > ALPHA_TOLERANCE,
            )

            composeRule.runOnIdle {
                harness.commitCollapsedRemoval(deleteTargetId)
            }

            val deleteAlphaSamples = mutableListOf<Float?>()
            val firstEnteringAlphaSamples = mutableListOf<Float?>()
            val secondEnteringAlphaSamples = mutableListOf<Float?>()
            val firstEnteringVisibilitySamples = mutableListOf<Boolean>()
            val secondEnteringVisibilitySamples = mutableListOf<Boolean>()
            repeat(12) {
                advanceAnimationClockBy(50)
                deleteAlphaSamples += readVisibleTextViewAlphaOrNull("Tall deleting memo")
                firstEnteringAlphaSamples += readVisibleTextViewAlphaOrNull(firstEnteringText)
                secondEnteringAlphaSamples += readVisibleTextViewAlphaOrNull(secondEnteringText)
                firstEnteringVisibilitySamples += isMemoCardVisibleOnScreen(firstEnteringId)
                secondEnteringVisibilitySamples += isMemoCardVisibleOnScreen(secondEnteringId)
            }

            val firstDeleteGoneIndex =
                deleteAlphaSamples.indexOfFirst { alpha ->
                    alpha == null || alpha <= ALPHA_TOLERANCE
                }
            org.junit.Assert.assertTrue(
                "Expected the deleted memo to become invisible during the sampled collapse window, but deleteAlphaSamples=$deleteAlphaSamples.",
                firstDeleteGoneIndex >= 0,
            )
            org.junit.Assert.assertTrue(
                "Expected the deleted memo to stay gone once it disappeared instead of flashing back, but deleteAlphaSamples=$deleteAlphaSamples.",
                deleteAlphaSamples
                    .drop(firstDeleteGoneIndex)
                    .all { alpha -> alpha == null || alpha <= ALPHA_TOLERANCE },
            )

            assertCardStaysVisibleAfterFirstOpaqueText(
                label = "first top-entry memo card",
                alphaSamples = firstEnteringAlphaSamples,
                visibilitySamples = firstEnteringVisibilitySamples,
            )
            assertCardStaysVisibleAfterFirstOpaqueText(
                label = "second top-entry memo card",
                alphaSamples = secondEnteringAlphaSamples,
                visibilitySamples = secondEnteringVisibilitySamples,
            )
        }
    }

    @org.junit.Test
    fun deletingAMemoInDeepScroll_withPagedList_keepsViewportStableAndDoesNotScrollJump() {
        val totalCount = 100
        val initialMemos = memoUiModels(count = totalCount).toImmutableList()
        val harness = PagedDeleteHarness(initialMemos)

        setPagedDeleteHarnessContent(harness)
        waitForTextView("Memo 00")

        // 1. Scroll past index 60 to index 70
        val targetScrollIndex = 70
        val targetMemoId = "memo-${targetScrollIndex.toString().padStart(2, '0')}"
        val targetMemoText = "Memo ${targetScrollIndex.toString().padStart(2, '0')}"

        composeRule.runOnIdle {
            harness.scope.launch {
                harness.listState.scrollToItem(targetScrollIndex, 0)
            }
        }
        waitForTextView(targetMemoText)

        // Verify we are indeed scrolled past the jump threshold (60)
        composeRule.runOnIdle {
            org.junit.Assert.assertTrue(
                "Expected first visible index to be >= 60",
                harness.listState.firstVisibleItemIndex >= 60
            )
        }

        withManualAnimationClock {
            // 2. Start deleting the first visible item
            composeRule.runOnIdle {
                harness.startDelete(targetMemoId)
            }

            advanceAnimationClockBy(180)

            // 3. Commit the removal (invalidates paging source and reloads)
            composeRule.runOnIdle {
                harness.commitCollapsedRemoval(targetMemoId)
            }

            // Wait for recomposition and layout settle
            advanceAnimationClockBy(200)
            composeRule.runOnIdle {
                // Assert that the list didn't jump to the top (which would have firstVisibleItemIndex < 10)
                val currentVisibleIndex = harness.listState.firstVisibleItemIndex
                org.junit.Assert.assertTrue(
                    "Expected viewport to remain stable in the deep scroll window (current index: $currentVisibleIndex), key should not jump.",
                    currentVisibleIndex >= 60
                )
            }
        }
    }

    @org.junit.Test
    fun deletingMemosInDeepScrollConsecutively_withPagedList_keepsViewportStableAndDoesNotScrollJump() {
        val totalCount = 100
        val initialMemos = memoUiModels(count = totalCount).toImmutableList()
        val harness = PagedDeleteHarness(initialMemos)

        setPagedDeleteHarnessContent(harness)
        waitForTextView("Memo 00")

        // 1. Scroll past index 60 to index 70
        val targetScrollIndex = 70
        val firstTargetId = "memo-70"
        val firstTargetText = "Memo 70"

        composeRule.runOnIdle {
            harness.scope.launch {
                harness.listState.scrollToItem(targetScrollIndex, 0)
            }
        }
        waitForTextView(firstTargetText)

        // Verify we are indeed scrolled past the jump threshold (60)
        composeRule.runOnIdle {
            org.junit.Assert.assertTrue(
                "Expected first visible index to be >= 60",
                harness.listState.firstVisibleItemIndex >= 60
            )
        }

        withManualAnimationClock {
            // First Delete
            composeRule.runOnIdle {
                harness.startDelete(firstTargetId)
            }
            advanceAnimationClockBy(180)

            composeRule.runOnIdle {
                harness.commitCollapsedRemoval(firstTargetId)
            }
            advanceAnimationClockBy(200)

            composeRule.runOnIdle {
                harness.onDeleteAnimationSettled(firstTargetId)
            }
            advanceAnimationClockBy(200)

            composeRule.runOnIdle {
                val currentVisibleIndex = harness.listState.firstVisibleItemIndex
                org.junit.Assert.assertTrue(
                    "Expected viewport to remain stable after first delete (current index: $currentVisibleIndex)",
                    currentVisibleIndex >= 60
                )
                org.junit.Assert.assertEquals(
                    "Expected first visible item to be the next alive neighbor after first delete",
                    "memo-71",
                    harness.listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key
                )
            }

            // Second Delete
            val secondTargetId = "memo-71"
            composeRule.runOnIdle {
                harness.startDelete(secondTargetId)
            }
            advanceAnimationClockBy(180)

            composeRule.runOnIdle {
                harness.commitCollapsedRemoval(secondTargetId)
            }
            advanceAnimationClockBy(200)

            composeRule.runOnIdle {
                harness.onDeleteAnimationSettled(secondTargetId)
            }
            advanceAnimationClockBy(200)

            composeRule.runOnIdle {
                val currentVisibleIndex = harness.listState.firstVisibleItemIndex
                org.junit.Assert.assertTrue(
                    "Expected viewport to remain stable after second delete (current index: $currentVisibleIndex)",
                    currentVisibleIndex >= 60
                )
                org.junit.Assert.assertEquals(
                    "Expected first visible item to be the next alive neighbor after second delete",
                    "memo-72",
                    harness.listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key
                )
            }
        }
    }

    @Composable
    private fun NewMemoHarnessContent(harness: NewMemoHarness) {
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        var nextNewMemoIndex by remember { mutableIntStateOf(0) }
        val pagedMemos =
            remember(harness.memos.value) {
                flowOf(PagingData.from(harness.memos.value))
            }.collectAsLazyPagingItems()
        val coordinator =
            remember(listState, scope, pagedMemos) {
                NewMemoCreationCoordinator<String>(
                    scope = scope,
                    isListAtAbsoluteTop = {
                        listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                    },
                    scrollListToAbsoluteTop = {
                        listState.scrollToItem(0)
                    },
                    createMemo = { content, _ ->
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
                    currentTopMemoId = {
                        pagedMemos.itemSnapshotList.items.firstOrNull()?.memo?.id
                    },
                    awaitNewTopItemAndReveal = { previousTopId ->
                        kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                            androidx.compose.runtime.snapshotFlow {
                                pagedMemos.itemSnapshotList.items.firstOrNull()?.memo?.id
                            }.first { topId -> topId != null && topId != previousTopId }
                        }
                        listState.scrollToItem(0)
                    },
                )
            }

        SideEffect {
            harness.listState = listState
            harness.scope = scope
            harness.coordinator = coordinator
        }

        ListRegressionHarnessTheme {
            MemoListContent(
                pagedMemos = pagedMemos,
                listState = listState,
                isRefreshing = false,
                onRefresh = {},
                onTodoClick = { _, _, _ -> },
                onReminderClick = { _, _ -> },
                dateFormat = "yyyy-MM-dd",
                timeFormat = "HH:mm",
                onTagClick = {},
                onImageClick = {},
                onShowMemoMenu = {},
            )
        }
    }

    @Composable
    private fun EditHarnessContent(harness: EditHarness) {
        val listState = rememberLazyListState()
        val pagedMemos =
            remember(harness.memos.value) {
                flowOf(PagingData.from(harness.memos.value))
            }.collectAsLazyPagingItems()

        SideEffect {
            harness.listState = listState
        }

        ListRegressionHarnessTheme {
            MemoListContent(
                pagedMemos = pagedMemos,
                listState = listState,
                isRefreshing = false,
                onRefresh = {},
                onTodoClick = { _, _, _ -> },
                onReminderClick = { _, _ -> },
                dateFormat = "yyyy-MM-dd",
                timeFormat = "HH:mm",
                onTagClick = {},
                onImageClick = {},
                onShowMemoMenu = {},
            )
        }
    }

    @Composable
    private fun DeleteHarnessContent(harness: DeleteHarness) {
        val deletingIds by harness.deletingIds.collectAsState()
        val listState = rememberLazyListState()
        val pagedMemos = harness.pagedMemos.collectAsLazyPagingItems()

        SideEffect {
            harness.listState = listState
        }

        ListRegressionHarnessTheme {
            MemoListContent(
                pagedMemos = pagedMemos,
                listState = listState,
                isRefreshing = false,
                onRefresh = {},
                onTodoClick = { _, _, _ -> },
                onReminderClick = { _, _ -> },
                dateFormat = "yyyy-MM-dd",
                timeFormat = "HH:mm",
                onTagClick = {},
                onImageClick = {},
                onShowMemoMenu = {},
            )
        }
    }

    @Composable
    private fun PagedDeleteHarnessContent(harness: PagedDeleteHarness) {
        val deletingIds by harness.deletingIds.collectAsState()
        val scope = rememberCoroutineScope()
        val pagedMemos = remember(harness, scope) {
            harness.flow.cachedIn(scope)
        }.collectAsLazyPagingItems()
        val listState = rememberLazyListState()

        SideEffect {
            harness.listState = listState
            harness.scope = scope
        }

        ListRegressionHarnessTheme {
            MemoListContent(
                pagedMemos = pagedMemos,
                listState = listState,
                isRefreshing = false,
                onRefresh = {},
                onTodoClick = { _, _, _ -> },
                onReminderClick = { _, _ -> },
                dateFormat = "yyyy-MM-dd",
                timeFormat = "HH:mm",
                onTagClick = {},
                onImageClick = {},
                onShowMemoMenu = {},
            )
        }
    }

    private fun setNewMemoHarnessContent(harness: NewMemoHarness) {
        composeRule.setContent {
            NewMemoHarnessContent(harness = harness)
        }
    }

    private fun setEditHarnessContent(harness: EditHarness) {
        composeRule.setContent {
            EditHarnessContent(harness = harness)
        }
    }

    private fun setDeleteHarnessContent(harness: DeleteHarness) {
        composeRule.setContent {
            DeleteHarnessContent(harness = harness)
        }
    }

    private fun setPagedDeleteHarnessContent(harness: PagedDeleteHarness) {
        composeRule.setContent {
            PagedDeleteHarnessContent(harness = harness)
        }
    }

    private fun setSwitchablePagedDeleteHarnessContent(activeHarness: MutableState<SwitchablePagedDeleteHarness>) {
        composeRule.setContent {
            key(activeHarness.value.scenarioKey) {
                PagedDeleteHarnessContent(harness = activeHarness.value.harness)
            }
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
                textView = findVisibleTextView(composeRule.activity.findViewById(android.R.id.content), expectedText)
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
        var top: Int? = null
        composeRule.runOnUiThread {
            val location = IntArray(2)
            val textView = findVisibleTextView(composeRule.activity.findViewById(android.R.id.content), expectedText)
            if (textView != null) {
                textView.getLocationOnScreen(location)
                top = location[1]
            }
        }
        return top
    }

    private fun readVisibleTextViewAlphaOrNull(expectedText: String): Float? {
        var alpha: Float? = null
        composeRule.runOnUiThread {
            alpha =
                findVisibleTextView(
                    composeRule.activity.findViewById(android.R.id.content),
                    expectedText,
                )?.alpha
        }
        return alpha
    }

    private fun sleepAndWaitForUi(millis: Long) {
        android.os.SystemClock.sleep(millis)
        composeRule.waitForIdle()
    }

    private fun advanceAnimationClockBy(millis: Long) {
        if (!composeRule.mainClock.autoAdvance && millis > 0) {
            android.os.SystemClock.sleep(millis)
        }
        composeRule.mainClock.advanceTimeBy(millis)
        composeRule.waitForIdle()
    }

    private inline fun withManualAnimationClock(block: () -> Unit) {
        val previousAutoAdvance = composeRule.mainClock.autoAdvance
        composeRule.mainClock.autoAdvance = false
        try {
            block()
        } finally {
            composeRule.mainClock.autoAdvance = previousAutoAdvance
        }
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

    private fun findVisibleTextView(
        root: View,
        expectedText: String,
    ): TextView? {
        val textView = findTextView(root, expectedText) ?: return null
        val visibleRect = android.graphics.Rect()
        return textView.takeIf {
            it.getGlobalVisibleRect(visibleRect) && !visibleRect.isEmpty
        }
    }

    private fun waitForMemoCard(
        memoId: String,
        timeoutMillis: Long = 5_000,
    ) {
        composeRule.waitUntil(timeoutMillis) {
            runCatching {
                readMemoCardBoundsInRoot(memoId)
            }.isSuccess
        }
    }

    private fun waitForMemoVisibleInLazyViewport(
        listState: LazyListState,
        memoId: String,
        timeoutMillis: Long = 5_000,
    ) {
        val deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000
        var lastVisibleKeys: List<Any> = emptyList()
        while (System.nanoTime() < deadlineNanos) {
            var isVisible = false
            composeRule.runOnUiThread {
                lastVisibleKeys = listState.layoutInfo.visibleItemsInfo.map { item -> item.key }
                isVisible = listState.layoutInfo.visibleItemsInfo.any { item -> item.key == memoId }
            }
            if (isVisible) {
                return
            }
            if (!composeRule.mainClock.autoAdvance) {
                android.os.SystemClock.sleep(16)
                composeRule.mainClock.advanceTimeByFrame()
            } else {
                android.os.SystemClock.sleep(16)
            }
            composeRule.runOnUiThread {}
        }
        throw androidx.compose.ui.test.ComposeTimeoutException(
            "Condition still not satisfied after $timeoutMillis ms; memoId=$memoId visibleKeys=$lastVisibleKeys",
        )
    }

    private fun isMemoVisibleInLazyViewport(
        listState: LazyListState,
        memoId: String,
    ): Boolean =
        runCatching {
            listState.layoutInfo.visibleItemsInfo.any { item -> item.key == memoId }
        }.getOrDefault(false)

    private fun readLazyLayoutGap(
        listState: LazyListState,
        firstMemoId: String,
        secondMemoId: String,
    ): Int? {
        var gap: Int? = null
        composeRule.runOnUiThread {
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            val first = visibleItems.firstOrNull { item -> item.key == firstMemoId }
            val second = visibleItems.firstOrNull { item -> item.key == secondMemoId }
            if (first != null && second != null) {
                gap = second.offset - (first.offset + first.size)
            }
        }
        return gap
    }

    private fun readMemoCardTopInRoot(memoId: String): Int = readMemoCardBoundsInRoot(memoId).top.toInt()

    private fun isMemoCardVisibleOnScreen(memoId: String): Boolean =
        runCatching {
            val bounds = readMemoCardBoundsInRoot(memoId)
            val rootBounds = composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            bounds.bottom > rootBounds.top && bounds.top < rootBounds.bottom
        }.getOrDefault(false)

    private fun assertAlphaSamplesStayVisibleAfterFirstAppearance(
        label: String,
        samples: List<Float?>,
    ) {
        val firstVisibleIndex =
            samples.indexOfFirst { alpha ->
                alpha != null && alpha > ALPHA_TOLERANCE
            }
        org.junit.Assert.assertTrue(
            "Expected $label to become visibly opaque during the sampled window, but alpha samples were $samples.",
            firstVisibleIndex >= 0,
        )
        org.junit.Assert.assertTrue(
            "Expected $label to stay visible once it first appeared instead of flickering, but alpha samples were $samples.",
            samples
                .drop(firstVisibleIndex)
                .all { alpha -> alpha != null && alpha > ALPHA_TOLERANCE },
        )
    }

    private fun assertVisibilitySamplesStayVisibleAfterFirstAppearance(
        label: String,
        samples: List<Boolean>,
    ) {
        val firstVisibleIndex = samples.indexOfFirst { it }
        org.junit.Assert.assertTrue(
            "Expected $label to become visible during the sampled window, but visibility samples were $samples.",
            firstVisibleIndex >= 0,
        )
        org.junit.Assert.assertTrue(
            "Expected $label to stay visible once it first appeared instead of leaving the screen and returning, but visibility samples were $samples.",
            samples.drop(firstVisibleIndex).all { it },
        )
    }

    private fun assertCardStaysVisibleAfterFirstOpaqueText(
        label: String,
        alphaSamples: List<Float?>,
        visibilitySamples: List<Boolean>,
    ) {
        val firstOpaqueIndex =
            alphaSamples.indexOfFirst { alpha ->
                alpha != null && alpha > ALPHA_TOLERANCE
            }
        org.junit.Assert.assertTrue(
            "Expected $label to become visibly opaque during the sampled window, but alphaSamples=$alphaSamples visibilitySamples=$visibilitySamples.",
            firstOpaqueIndex >= 0,
        )
        org.junit.Assert.assertTrue(
            "Expected $label to stay on-screen once its text first became visibly opaque, but alphaSamples=$alphaSamples visibilitySamples=$visibilitySamples.",
            visibilitySamples.drop(firstOpaqueIndex).all { it },
        )
    }

    private fun assertMemoCardsStaySeparated(
        editedMemoId: String,
        followingMemoId: String,
    ) {
        waitForMemoCard(editedMemoId)
        waitForMemoCard(followingMemoId)

        val editedBounds = readMemoCardBoundsInRoot(editedMemoId)
        val followingBounds = readMemoCardBoundsInRoot(followingMemoId)
        val actualGapPx = followingBounds.top - editedBounds.bottom
        val expectedGapPx = expectedMemoCardSpacingPx()

        org.junit.Assert.assertTrue(
            "Expected memo cards to stay separated by about $expectedGapPx px after an in-place edit, but the edited memo bottom was ${editedBounds.bottom}, the following memo top was ${followingBounds.top}, and the gap was $actualGapPx.",
            kotlin.math.abs(actualGapPx - expectedGapPx) <= CARD_GAP_TOLERANCE_PX,
        )
    }

    private fun readMemoCardBoundsInRoot(memoId: String): Rect =
        composeRule
            .onNodeWithTag(BenchmarkAnchorContract.memoCard(memoId), useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

    private fun expectedMemoCardSpacingPx(): Float {
        var spacingPx = 0f
        composeRule.runOnIdle {
            spacingPx =
                with(composeRule.activity.resources.displayMetrics) {
                    MEMO_CARD_SPACING_DP * density
                }
        }
        return spacingPx
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

    private fun updatedMemoUiModel(
        existing: MemoUiModel,
        content: String,
    ): MemoUiModel =
        memoUiModel(
            id = existing.memo.id,
            content = content,
        )

    @Composable
    private fun ListRegressionHarnessTheme(content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalBenchmarkAnchorConfig provides BenchmarkAnchorConfig(enabled = true),
        ) {
            MaterialTheme {
                content()
            }
        }
    }

    private class NewMemoHarness(
        val memos: MutableState<ImmutableList<MemoUiModel>>,
    ) {
        lateinit var listState: LazyListState
        lateinit var scope: CoroutineScope
        lateinit var coordinator: NewMemoCreationCoordinator<String>
    }

    private inner class EditHarness(
        val memos: MutableState<ImmutableList<MemoUiModel>>,
    ) {
        lateinit var listState: LazyListState

        fun updateMemo(
            id: String,
            content: String,
        ) {
            memos.value =
                memos.value
                    .map { uiModel ->
                        if (uiModel.memo.id == id) {
                            updatedMemoUiModel(existing = uiModel, content = content)
                        } else {
                            uiModel
                        }
                    }.toImmutableList()
        }
    }

    private class DeleteHarness(
        val sourceMemos: MutableState<ImmutableList<MemoUiModel>>,
    ) {
        lateinit var listState: LazyListState
        val deletingIds = MutableStateFlow(emptySet<String>())
        val pagedMemos = MutableStateFlow(PagingData.from(sourceMemos.value))

        fun startDelete(id: String) {
            deletingIds.value = setOf(id)
        }

        fun commitCollapsedRemoval(id: String) {
            sourceMemos.value =
                sourceMemos.value
                    .filterNot { item -> item.memo.id == id }
                    .toImmutableList()
            pagedMemos.value = PagingData.from(sourceMemos.value)
        }

        fun onDeleteAnimationSettled(id: String) {
            deletingIds.value = deletingIds.value - id
        }
    }

    private class PagedDeleteHarness(
        initialMemos: ImmutableList<MemoUiModel>,
    ) {
        lateinit var listState: LazyListState
        lateinit var scope: CoroutineScope
        private var databaseMemos = initialMemos.toList()
        val deletingIds = MutableStateFlow(emptySet<String>())
        private var currentPagingSource: SimplePagingSource? = null

        val pager = Pager(
            config = PagingConfig(
                pageSize = 20,
                prefetchDistance = 5,
                enablePlaceholders = true,
                jumpThreshold = 60
            ),
            pagingSourceFactory = {
                SimplePagingSource(databaseMemos).also {
                    currentPagingSource = it
                }
            }
        )

        val flow = pager.flow

        val itemCount: Int
            get() = databaseMemos.size

        fun startDelete(id: String) {
            deletingIds.value = setOf(id)
        }

        fun commitCollapsedRemoval(id: String) {
            databaseMemos = databaseMemos.filterNot { item -> item.memo.id == id }
            currentPagingSource?.invalidate()
        }

        fun onDeleteAnimationSettled(id: String) {
            deletingIds.value = deletingIds.value - id
        }
    }

    private class SimplePagingSource(
        private val data: List<MemoUiModel>
    ) : PagingSource<Int, MemoUiModel>() {
        override val jumpingSupported: Boolean
            get() = true

        override fun getRefreshKey(state: PagingState<Int, MemoUiModel>): Int? {
            return state.anchorPosition
        }

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MemoUiModel> {
            val totalCount = data.size
            if (totalCount == 0) {
                return LoadResult.Page(emptyList(), null, null, 0, 0)
            }
            val key = params.key ?: 0
            val loadSize = params.loadSize
            // Center the loaded page around the key
            val start = (key - loadSize / 2).coerceIn(0, totalCount - 1)
            val end = (start + loadSize).coerceAtMost(totalCount)
            val pageData = data.subList(start, end)
            return LoadResult.Page(
                data = pageData,
                prevKey = if (start > 0) start else null,
                nextKey = if (end < totalCount) end else null,
                itemsBefore = start,
                itemsAfter = totalCount - end
            )
        }
    }

    private data class SwitchablePagedDeleteHarness(
        val scenarioKey: String,
        val harness: PagedDeleteHarness,
    )

    private companion object {
        const val ALPHA_TOLERANCE = 0.05f
        const val POSITION_TOLERANCE_PX = 2
        const val MEMO_CARD_SPACING_DP = 12f
        const val CARD_GAP_TOLERANCE_PX = 3f
        const val MIN_HEIGHT_DELTA_PX = 80f
        val LONG_EDITED_MEMO_CONTENT =
            """
            Edited memo after save.
            Line 01 keeps the card noticeably taller.
            Line 02 keeps the card noticeably taller.
            Line 03 keeps the card noticeably taller.
            Line 04 keeps the card noticeably taller.
            Line 05 keeps the card noticeably taller.
            Line 06 keeps the card noticeably taller.
            Line 07 keeps the card noticeably taller.
            Line 08 keeps the card noticeably taller.
            Line 09 keeps the card noticeably taller.
            Line 10 keeps the card noticeably taller.
            Line 11 keeps the card noticeably taller.
            Line 12 keeps the card noticeably taller.
            Line 13 keeps the card noticeably taller.
            Line 14 keeps the card noticeably taller.
            Line 15 keeps the card noticeably taller.
            Line 16 keeps the card noticeably taller.
            Line 17 keeps the card noticeably taller.
            Line 18 keeps the card noticeably taller.
            """.trimIndent()
        val VERY_LONG_DELETE_TARGET_CONTENT =
            buildString {
                appendLine("Tall deleting memo.")
                repeat(80) { index ->
                    appendLine(
                        "Tall deleting memo line ${index.toString().padStart(2, '0')} keeps the card far below the fold.",
                    )
                }
            }.trimEnd()
        const val SHORT_EDITED_MEMO_CONTENT = "Short memo again."
    }
}
