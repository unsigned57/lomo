package com.lomo.app.feature.main

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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.feature.common.RetainedVisibleListTracker
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
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/*
 * Test Contract:
 * - Unit under test: MainScreen list prepend visibility, same-id edit remeasure, and delete-motion regression
 *   behavior on a real device.
 * - Behavior focus: prepending a new memo must keep the inserted card in the visible top viewport whether the
 *   list is already at the top or must scroll back first; editing an existing memo in place must force the row to
 *   remeasure so the following memo stays separated after both growth and shrink; deleting a middle memo must move
 *   the following memo upward in a single continuous settle without a downward rebound.
 * - Observable outcomes: new memo TextView presence in the composed viewport after prepend, list anchor reset to
 *   absolute top, memo-card gap staying at the configured item spacing after same-id content edits, and monotonic
 *   upward screen-Y movement for the following memo once collapse begins.
 * - Red phase: Fails before the fix when the first memo is edited in place with the same id and a much different
 *   height, leaving the next memo overlapped or separated by a stale oversized gap until the list is scrolled.
 * - Excludes: Hilt wiring, repository persistence, detailed easing-curve fidelity, and full app navigation.
 */
@RunWith(AndroidJUnit4::class)
class MainScreenListRegressionTest {
    @Suppress("DEPRECATION")
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
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

    @Test
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

    @Test
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
        assertTrue(
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
        assertTrue(
            "Expected the edited memo to shrink again after the short-content update, but height only changed from $expandedEditedHeight to $shrunkEditedHeight.",
            shrunkEditedHeight <= expandedEditedHeight - MIN_HEIGHT_DELTA_PX,
        )
        assertMemoCardsStaySeparated(
            editedMemoId = editedMemoId,
            followingMemoId = followingMemoId,
        )
    }

    @Test
    fun deletingAMiddleMemo_movesTheFollowingMemoUpwardWithoutRebound() {
        val deleteTargetId = "memo-01"
        val followingMemoText = "Memo 02"
        val harness = DeleteHarness(mutableStateOf(memoUiModels(count = 6)))

        setDeleteHarnessContent(harness = harness)
        waitForTextView(followingMemoText)

        val beforeDeleteY = readTextViewScreenTop(followingMemoText)

        composeRule.runOnIdle {
            harness.startDelete(deleteTargetId)
        }

        sleepAndWaitForUi(180)
        val midFadeY = readTextViewScreenTop(followingMemoText)
        assertTrue(
            "Expected the following memo to hold position during delete fade, but Y moved from $beforeDeleteY to $midFadeY.",
            kotlin.math.abs(midFadeY - beforeDeleteY) <= POSITION_TOLERANCE_PX,
        )

        composeRule.runOnIdle {
            harness.commitCollapsedRemoval(deleteTargetId)
        }

        val collapseSamples = mutableListOf<Int>()
        repeat(10) {
            sleepAndWaitForUi(50)
            collapseSamples += readTextViewScreenTop(followingMemoText)
        }

        assertTrue(
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
                assertTrue(
                    "Expected the following memo to keep moving upward or stay settled once collapse started, but sampled Y positions were $collapseSamples.",
                    currentY <= previousY + POSITION_TOLERANCE_PX,
                )
            }
            previousY = currentY
        }
    }

    @Composable
    private fun NewMemoHarnessContent(harness: NewMemoHarness) {
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
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

        SideEffect {
            harness.listState = listState
            harness.scope = scope
            harness.coordinator = coordinator
        }

        ListRegressionHarnessTheme {
            MemoListContent(
                memos = harness.memos.value,
                deletingMemoIds = persistentSetOf(),
                collapsingMemoIds = persistentSetOf(),
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

    @Composable
    private fun EditHarnessContent(harness: EditHarness) {
        val listState = rememberLazyListState()

        SideEffect {
            harness.listState = listState
        }

        ListRegressionHarnessTheme {
            MemoListContent(
                memos = harness.memos.value,
                deletingMemoIds = persistentSetOf(),
                collapsingMemoIds = persistentSetOf(),
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

    @Composable
    private fun DeleteHarnessContent(harness: DeleteHarness) {
        val scope = rememberCoroutineScope()
        val tracker =
            remember(scope) {
                RetainedVisibleListTracker(
                    scope = scope,
                    sourceItemsProvider = { harness.sourceMemos.value },
                    deletingIds = harness.deletingIds,
                    retainedIds = harness.collapsingIds,
                    itemId = { item: MemoUiModel -> item.memo.id },
                )
            }
        val collapsingIds by harness.collapsingIds.collectAsState()
        val visibleMemos by tracker.visibleItems.collectAsState()
        val deletingIds by harness.deletingIds.collectAsState()

        LaunchedEffect(harness.sourceMemos.value, collapsingIds) {
            tracker.reconcile(
                sourceItems = harness.sourceMemos.value,
                retainedIdsSnapshot = collapsingIds,
            )
        }

        ListRegressionHarnessTheme {
            MemoListContent(
                memos = visibleMemos.toImmutableList(),
                deletingMemoIds = deletingIds.toPersistentSet(),
                collapsingMemoIds = collapsingIds.toPersistentSet(),
                listState = rememberLazyListState(),
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

    private fun sleepAndWaitForUi(millis: Long) {
        Thread.sleep(millis)
        composeRule.waitForIdle()
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

        assertTrue(
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
        val deletingIds = MutableStateFlow(emptySet<String>())
        val collapsingIds = MutableStateFlow(emptySet<String>())

        fun startDelete(id: String) {
            deletingIds.value = setOf(id)
        }

        fun commitCollapsedRemoval(id: String) {
            sourceMemos.value =
                sourceMemos.value
                    .filterNot { item -> item.memo.id == id }
                    .toImmutableList()
            collapsingIds.value = setOf(id)
        }
    }

    private companion object {
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
        const val SHORT_EDITED_MEMO_CONTENT = "Short memo again."
    }
}
