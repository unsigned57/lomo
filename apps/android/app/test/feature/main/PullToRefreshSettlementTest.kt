package com.lomo.app.feature.main

import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.BroadcastFrameClock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * Capability: a refresh settlement remains one gesture transaction until both surfaces settle;
 * owning layer: app; priority: P1.
 * Scenarios:
 * - Given the ball has settled before the content, when a reverse gesture checks animation state,
 *   then it cannot interrupt the remaining content return and leave a blank strip.
 * Observable outcomes: animation admission and final content distance with real frame samples.
 * TDD proof: target/lomo/reports/manual/directory-interaction-bugs/lomo-viewport-red.log reports false animation admission while content is returning;
 * GREEN: target/lomo/reports/manual/directory-interaction-bugs/lomo-bugs-app-green.log.
 * Excludes: provider refresh duration and physical device frame timing.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
class PullToRefreshSettlementTest :
    FunSpec({
        test(
            "given the indicator finishes first then the content still owns animation admission until its return ends",
        ) {
            runTest {
                val frames = BroadcastFrameClock()
                val indicator = object : PullToRefreshState {
                    override val isAnimating = false
                    override var distanceFraction = 0f
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
                var refreshing = false
                val state = MemoListPullToRefreshState(indicator, tween(200), isRefreshing = { refreshing })
                state.snapTo(1.2f)
                refreshing = true
                val returning = launch(frames) { state.animateToThreshold() }
                runCurrent()
                frames.sendFrame(0L)
                runCurrent()
                frames.sendFrame(100_000_000L)
                runCurrent()
                (state.contentDistanceFraction > 0f) shouldBe true
                state.isAnimating shouldBe true
                frames.sendFrame(300_000_000L)
                runCurrent()
                returning.isCompleted shouldBe true
                state.contentDistanceFraction shouldBe 0f
                state.isAnimating shouldBe false
                state.snapTo(0f)
                state.contentDistanceFraction shouldBe 0f
            }
        }
    })
