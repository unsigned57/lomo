package com.lomo.ui.component.navigation

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/*
 * Test Change Justification:
 * Reason category: API reshaped to satisfy an activated platform lint contract.
 * Old behavior/assertion being replaced: a shared suspend consumer took the progress flow and
 * onProgress/onCommit/onCancel callbacks.
 * Why old assertion is no longer correct: androidx NoCollectCallFound requires the
 * PredictiveBackHandler lambda itself to collect its event flow, so the shared unit is now a
 * transformer and each caller collects, then commits after normal completion.
 * Coverage preserved by: the same three scenarios (completion, cancellation restore, invalid
 * progress) asserted through collect-then-commit, the exact shape every caller now uses.
 * Why this is not fitting the test to the implementation: preview values, the destination and the
 * propagated failure remain the observable outcomes; only the call shape changed.
 *
 * Behavior Contract:
 * Capability: predictive back previews without premature navigation; owner: ui-components; P1.
 * Scenarios:
 * Given progress events, when the gesture completes, then the destination changes exactly once.
 * Given cancellation, when collection stops, then the preview restores and the destination stays.
 * Given an invalid progress value, when read at the boundary, then it is rejected before commit.
 * Observable outcomes: preview values, destination and propagated failure.
 * TDD proof: exercise this contract with the shared progress transformer and both cancellation
 * paths; RED before the transformer existed, GREEN after.
 * Excludes: Android gesture dispatch and device rendering.
 */
class PredictiveBackGestureTest : UiComponentsFunSpec() {
    init {
        test("given a completed gesture when collecting progress then navigation commits after the preview") {
            runTest {
                val observed = mutableListOf<Float>()
                var destination = "editor"
                flowOf(0f, 0.4f, 1f)
                    .predictiveBackProgress(onCancel = { error("Completed gesture must not cancel") })
                    .collect {
                        observed += it
                        destination shouldBe "editor"
                    }
                destination = "list"
                observed shouldBe listOf(0f, 0.4f, 1f)
                destination shouldBe "list"
            }
        }
        test("given a cancelled gesture when collecting stops then the preview restores without navigating") {
            runTest {
                var preview = 0f
                var destination = "editor"
                shouldThrow<CancellationException> {
                    flow {
                        emit(0.6f)
                        throw CancellationException("gesture cancelled")
                    }.predictiveBackProgress(onCancel = { preview = 0f })
                        .collect { preview = it }
                    destination = "list"
                }
                preview shouldBe 0f
                destination shouldBe "editor"
            }
        }
        test("given invalid upstream progress when collecting then no navigation occurs") {
            runTest {
                listOf(Float.NaN, -0.1f, 1.1f).forEach { invalid ->
                    var destination = "editor"
                    shouldThrow<IllegalArgumentException> {
                        flowOf(invalid)
                            .predictiveBackProgress(onCancel = { error("Invalid input is not gesture cancellation") })
                            .collect { error("Invalid progress must not reach the surface") }
                        destination = "list"
                    }
                    destination shouldBe "editor"
                }
            }
        }
    }
}
