/*
 * Behavior Contract:
 * Capability: cards follow a pull and return on release while refresh remains visible; owner app; P1.
 * Scenarios:
 * - Given a partial drag, when snapped then released below threshold, then content and indicator return to zero.
 * - Given a threshold-crossing drag, when refresh starts, then content returns to zero while the indicator stays at threshold.
 * - Given refresh completion, when another drag begins, then content follows the new distance without stale offset.
 * Observable outcomes: content and indicator distance fractions across the gesture lifecycle.
 * TDD proof: target/lomo/reports/manual/directory-interaction-bugs/lomo-refresh-held-red.log
 * reports contentDistanceFraction=1 instead of 0 after the held refresh distance is snapped.
 * Earlier lifecycle scenarios had no preimplementation motion RED; their assertions remain intact.
 * Test Change Justification:
 * Reason category: canonical refresh phase now constrains content displacement.
 * Old behavior/assertion being replaced: no result assertions are removed or weakened; fixtures now supply refresh state.
 * Why old assertion is no longer correct: prior lifecycle assertions still hold, but did not cover snaps while refreshing.
 * Coverage preserved by: release below threshold, release above threshold, held snaps and the next independent drag.
 * Why this is not fitting the test to the implementation: the new scenario fails against the previous public state behavior.
 * Excludes: nested-scroll dispatch and physical frame timing.
 */
package com.lomo.app.feature.main

import androidx.compose.animation.core.snap
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.MonotonicFrameClock
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
class MemoListPullToRefreshStateTest : AppFunSpec() {
    init {
        test("given a refreshing indicator when a reverse scroll snaps its held distance then content stays at rest") {
            runTest {
                withContext(ImmediateFrameClock) {
                    val indicator = IndicatorState()
                    var refreshing = false
                    val state = MemoListPullToRefreshState(indicator, snap(), isRefreshing = { refreshing })
                    state.snapTo(1.3f)
                    refreshing = true
                    state.animateToThreshold()
                    state.snapTo(1f)
                    state.distanceFraction shouldBe 1f
                    state.contentDistanceFraction shouldBe 0f
                    state.snapTo(1.1f)
                    state.contentDistanceFraction shouldBe 0f
                    refreshing = false
                    state.animateToHidden()
                    state.snapTo(0.4f)
                    state.contentDistanceFraction shouldBe 0.4f
                }
            }
        }
        test("given a pull released below threshold then both surfaces return to rest") {
            runTest {
                withContext(ImmediateFrameClock) {
                    val indicator = IndicatorState()
                    val state = MemoListPullToRefreshState(indicator, snap(), isRefreshing = { false })
                    state.snapTo(0.6f)
                    state.contentDistanceFraction shouldBe 0.6f
                    state.distanceFraction shouldBe 0.6f
                    state.animateToHidden()
                    state.contentDistanceFraction shouldBe 0f
                    state.distanceFraction shouldBe 0f
                }
            }
        }
        test(
            "given a refresh when released then cards return while indicator stays visible and the next pull follows",
        ) {
            runTest {
                withContext(ImmediateFrameClock) {
                    val indicator = IndicatorState()
                    var refreshing = false
                    val state = MemoListPullToRefreshState(indicator, snap(), isRefreshing = { refreshing })
                    state.snapTo(1.3f)
                    refreshing = true
                    state.animateToThreshold()
                    state.contentDistanceFraction shouldBe 0f
                    state.distanceFraction shouldBe 1f
                    refreshing = false
                    state.animateToHidden()
                    state.snapTo(0.4f)
                    state.contentDistanceFraction shouldBe 0.4f
                    state.distanceFraction shouldBe 0.4f
                }
            }
        }
    }
}

private object ImmediateFrameClock : MonotonicFrameClock {
    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(0L)
}

@OptIn(ExperimentalMaterial3Api::class)
private class IndicatorState : PullToRefreshState {
    override val isAnimating: Boolean = false
    override var distanceFraction: Float = 0f
        private set
    override suspend fun snapTo(targetValue: Float) {
        distanceFraction = targetValue
    }
    override suspend fun animateToThreshold() {
        distanceFraction = 1f
    }
    override suspend fun animateToHidden() {
        distanceFraction = 0f
    }
}
