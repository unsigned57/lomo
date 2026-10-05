package com.lomo.ui.component.navigation

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/**
 * Behavior Contract:
 * Capability: predictive back previews without premature navigation; owner: ui-components; P1.
 * Scenarios:
 * Given progress events, when the gesture completes, then the destination changes exactly once.
 * Given cancellation, when collection stops, then the preview restores and the destination stays.
 * Given an invalid progress value, when read at the boundary, then it is rejected before commit.
 * Observable outcomes: preview values, destination and propagated failure.
 * TDD proof: exercise this contract with the shared gesture collector and both cancellation paths.
 * Excludes: Android gesture dispatch and device rendering.
 */
class PredictiveBackGestureTest : UiComponentsFunSpec() {
    init {
        test("given a completed gesture when collecting progress then navigation commits after the preview") {
            runTest {
                val observed = mutableListOf<Float>()
                var destination = "editor"
                consumePredictiveBackGesture(
                    progress = flowOf(0f, 0.4f, 1f),
                    onProgress = { observed += it; destination shouldBe "editor" },
                    onCommit = { destination = "list" },
                    onCancel = { error("Completed gesture must not cancel") },
                )
                observed shouldBe listOf(0f, 0.4f, 1f)
                destination shouldBe "list"
            }
        }
        test("given a cancelled gesture when collecting stops then the preview restores without navigating") {
            runTest {
                var preview = 0f
                var destination = "editor"
                shouldThrow<CancellationException> {
                    consumePredictiveBackGesture(
                        progress = flow { emit(0.6f); throw CancellationException("gesture cancelled") },
                        onProgress = { preview = it },
                        onCommit = { destination = "list" },
                        onCancel = { preview = 0f },
                    )
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
                        consumePredictiveBackGesture(
                            progress = flowOf(invalid),
                            onProgress = { error("Invalid progress must not reach the surface") },
                            onCommit = { destination = "list" },
                            onCancel = { error("Invalid input is not gesture cancellation") },
                        )
                    }
                    destination shouldBe "editor"
                }
            }
        }
    }
}
