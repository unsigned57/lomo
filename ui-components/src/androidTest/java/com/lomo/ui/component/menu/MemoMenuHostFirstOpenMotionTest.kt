package com.lomo.ui.component.menu

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lomo.ui.benchmark.BenchmarkAnchorConfig
import com.lomo.ui.benchmark.LocalBenchmarkAnchorConfig
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/*
 * Test Contract:
 * - Unit under test: MemoMenuHost first-open bottom-sheet motion on a real device.
 * - Behavior focus: the first menu open after host composition must enter from an off-screen start and settle
 *   over time instead of appearing immediately at the final expanded position.
 * - Observable outcomes: sampled benchmark-root top positions across animation frames and final visible menu root.
 * - Red phase: Fails before the fix when the first visible sampled frame already matches the final settled top and
 *   no upward settling motion is observable during first open.
 * - Excludes: exact easing fidelity, menu action business callbacks, and full MainScreen wiring.
 */
@RunWith(AndroidJUnit4::class)
class MemoMenuHostFirstOpenMotionTest {
    private companion object {
        const val MENU_ROOT_TAG = "memo_menu_root_test"
        const val POSITION_TOLERANCE_PX = 1f
        const val SAMPLE_FRAMES = 40
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun firstOpen_entersBeforeReachingItsSettledPosition() {
        var showMenu: ((MemoMenuState) -> Unit)? = null

        composeRule.setContent {
            CompositionLocalProvider(
                LocalBenchmarkAnchorConfig provides BenchmarkAnchorConfig(enabled = true),
            ) {
                MaterialTheme {
                    MemoMenuHost(
                        onEdit = {},
                        onDelete = {},
                        benchmarkRootTag = MENU_ROOT_TAG,
                    ) { launcher ->
                        SideEffect {
                            showMenu = launcher
                        }
                    }
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { showMenu != null }

        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle {
            checkNotNull(showMenu).invoke(sampleMenuState())
        }

        val sampledVisibleTops = mutableListOf<Float>()
        repeat(SAMPLE_FRAMES) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            readMenuTopOrNull()?.let(sampledVisibleTops::add)
        }
        composeRule.mainClock.autoAdvance = true

        assertTrue(
            "Expected the memo menu to appear during first-open sampling, but collected no visible tops.",
            sampledVisibleTops.isNotEmpty(),
        )

        val settledTop = sampledVisibleTops.last()
        assertTrue(
            "Expected first visible frame to start below the final settled top, but sampled tops were $sampledVisibleTops.",
            sampledVisibleTops.first() > settledTop + POSITION_TOLERANCE_PX,
        )
        assertTrue(
            "Expected the first-open menu to move upward toward its settled position, but sampled tops were $sampledVisibleTops.",
            sampledVisibleTops.zipWithNext().any { (previousTop, currentTop) ->
                currentTop < previousTop - POSITION_TOLERANCE_PX
            },
        )
    }

    private fun readMenuTopOrNull(): Float? =
        composeRule
            .onAllNodesWithTag(MENU_ROOT_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.boundsInRoot
            ?.top

    private fun sampleMenuState(): MemoMenuState =
        MemoMenuState(
            wordCount = 12,
            createdTime = "2026-04-10 09:30",
            content = "First-open menu motion regression target",
            isPinned = false,
        )
}
