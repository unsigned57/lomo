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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lomo.app.feature.common.RetainedVisibleListTracker
import com.lomo.domain.model.Memo
import kotlinx.collections.immutable.persistentListOf
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
 * - Unit under test: MainScreen list prepend visibility and delete-motion regression behavior on a real device.
 * - Behavior focus: prepending a new memo must keep the inserted card in the visible top viewport whether the
 *   list is already at the top or must scroll back first; deleting a middle memo must move the following memo
 *   upward in a single continuous settle without a downward rebound.
 * - Observable outcomes: new memo TextView presence in the composed viewport after prepend, list anchor reset to
 *   absolute top, stable off-top scroll recovery before prepend, and monotonic upward screen-Y movement for the
 *   following memo once collapse begins.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: Hilt wiring, repository persistence, detailed easing-curve fidelity, and full app navigation.
 */
@RunWith(AndroidJUnit4::class)
class MainScreenListRegressionTest {
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
                            listOf(
                                memoUiModel(
                                    id = "new-$nextNewMemoIndex",
                                    content = content,
                                ),
                            ) + harness.memos.value
                    },
                )
            }

        SideEffect {
            harness.listState = listState
            harness.scope = scope
            harness.coordinator = coordinator
        }

        MaterialTheme {
            MemoListContent(
                memos = harness.memos.value,
                deletingMemoIds = MutableStateFlow(emptySet()),
                collapsingMemoIds = MutableStateFlow(emptySet()),
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

        LaunchedEffect(harness.sourceMemos.value, collapsingIds) {
            tracker.reconcile(
                sourceItems = harness.sourceMemos.value,
                retainedIdsSnapshot = collapsingIds,
            )
        }

        MaterialTheme {
            MemoListContent(
                memos = visibleMemos,
                deletingMemoIds = harness.deletingIds,
                collapsingMemoIds = harness.collapsingIds,
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

    private fun memoUiModels(count: Int): List<MemoUiModel> =
        List(count) { index ->
            memoUiModel(
                id = "memo-${index.toString().padStart(2, '0')}",
                content = "Memo ${index.toString().padStart(2, '0')}",
            )
        }

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

    private class NewMemoHarness(
        val memos: MutableState<List<MemoUiModel>>,
    ) {
        lateinit var listState: LazyListState
        lateinit var scope: CoroutineScope
        lateinit var coordinator: NewMemoCreationCoordinator<String>
    }

    private class DeleteHarness(
        val sourceMemos: MutableState<List<MemoUiModel>>,
    ) {
        val deletingIds = MutableStateFlow(emptySet<String>())
        val collapsingIds = MutableStateFlow(emptySet<String>())

        fun startDelete(id: String) {
            deletingIds.value = setOf(id)
        }

        fun commitCollapsedRemoval(id: String) {
            sourceMemos.value = sourceMemos.value.filterNot { item -> item.memo.id == id }
            collapsingIds.value = setOf(id)
        }
    }

    private companion object {
        const val POSITION_TOLERANCE_PX = 2
    }
}
