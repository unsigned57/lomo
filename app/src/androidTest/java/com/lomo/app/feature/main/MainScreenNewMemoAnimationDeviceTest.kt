package com.lomo.app.feature.main

/**
 * Behavior Contract:
 * - Unit under test: Main-screen new-memo insert animation on a real device.
 * - Owning layer: app
 * - Priority tier: P2
 * - Capability: verify new-memo insert animation settles with the new memo visible at top.
 *
 * Scenarios:
 * - Given prepend at top, when inserting, then the new memo becomes visible in the viewport.
 * - Given prepend away from top, when inserting, then the list scrolls to top and the new memo becomes visible.
 *
 * Observable outcomes:
 * - The new memo's text view is found in the viewport after the insert settles.
 *
 * TDD proof:
 * - The previous test version depended on the deleted NewMemoInsertAnimationSession 4-phase
 *   handshake. This rewrite asserts terminal state (new memo visible) per plan.md:83
 *   "断言终态，不断言中间帧".
 *
 * Excludes:
 * - repository persistence, Hilt wiring, exact easing curves, and full MainScreen integration.
 *
 * Test Change Justification:
 * - Reason category: App layer restructuring replaced page-based memo retention and viewport delete animations with LomoList system, extracted provider settings dialogs, and added conflict/startup orchestration.
 * - Old behavior/assertion being replaced: previous app-layer tests relied on monolithic settings dialogs, DeleteViewportEntry animation system, and pre-LomoList memo retention.
 * - Why old assertion is no longer correct: the app layer was restructured: settings dialogs are now provider-specific, DeleteViewportEntry files are removed in favor of LomoList components, and paged memo content uses new pagination source.
 * - Coverage preserved by: all existing scenarios retained; assertions updated to use new LomoList animation contracts, provider settings surfaces, and paging source APIs.
 * - Why this is not fitting the test to the implementation: tests verify observable ViewModel state, UI coordinator behavior, and screen rendering outcomes, not internal animation or dialog mechanics.
 */

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.lomo.ui.component.common.EnterAnimationRegistry
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import com.lomo.domain.model.Memo
import java.time.LocalDate
import java.time.ZoneId

@org.junit.runner.RunWith(AndroidJUnit4::class)
class MainScreenNewMemoAnimationDeviceTest {
    @Suppress("DEPRECATION")
    @get:org.junit.Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @org.junit.Test
    fun prependAtTop_revealsNewMemoInTheVisibleViewport() {
        val harness = AnimationHarness(mutableStateOf(memoUiModels(count = 24)))
        val newMemoText = "New memo staged reveal"

        setContent(harness = harness)
        waitForTextView("Memo 00")

        composeRule.runOnIdle {
            harness.coordinator.submit(newMemoText)
        }

        waitForListAtAbsoluteTop(harness.listState)
        waitForTextView(newMemoText)
    }

    @org.junit.Test
    fun prependAwayFromTop_scrollsBackAndRevealsNewMemo() {
        val harness = AnimationHarness(mutableStateOf(memoUiModels(count = 28)))
        val newMemoText = "New memo after scroll back"

        setContent(harness = harness)
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

    @Composable
    private fun AnimationHarnessContent(harness: AnimationHarness) {
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        var nextNewMemoIndex by remember { mutableIntStateOf(0) }
        val enterAnimationRegistry = remember { EnterAnimationRegistry() }
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
                        val newTopId =
                            kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                                androidx.compose.runtime.snapshotFlow {
                                    pagedMemos.itemSnapshotList.items.firstOrNull()?.memo?.id
                                }.first { topId -> topId != null && topId != previousTopId }
                            }
                        if (newTopId != null) {
                            enterAnimationRegistry.beginEnter(newTopId)
                            listState.scrollToItem(0)
                        }
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
                pagedMemos = pagedMemos,
                enterAnimationRegistry = enterAnimationRegistry,
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

    private fun setContent(harness: AnimationHarness) {
        composeRule.setContent {
            AnimationHarnessContent(harness = harness)
        }
    }

    private fun waitForListAtAbsoluteTop(
        listState: androidx.compose.foundation.lazy.LazyListState,
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

    private class AnimationHarness(
        val memos: MutableState<ImmutableList<MemoUiModel>>,
    ) {
        lateinit var listState: androidx.compose.foundation.lazy.LazyListState
        lateinit var scope: CoroutineScope
        lateinit var coordinator: NewMemoCreationCoordinator<String>
    }
}
