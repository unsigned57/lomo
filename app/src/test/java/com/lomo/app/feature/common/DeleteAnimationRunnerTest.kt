package com.lomo.app.feature.common

/*
 * Behavior Contract:
 * - Unit under test: runDeleteAnimationWithRollback
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: run a delete action with animated exit markers in a registry, rolling back the marker on failure or cancellation.
 *
 * Scenarios:
 * - Given an item id and registry, when the delete action succeeds, then the deleting marker is retained in the registry.
 * - Given an item id and registry, when the delete action throws, then the deleting marker is rolled back.
 * - Given an item id and registry, when the delete action is cancelled, then the deleting marker is rolled back.
 *
 * Observable outcomes:
 * - ExitAnimationRegistry entry presence and result success/failure.
 *
 * TDD proof:
 * - Fails before the fix because DeleteViewportEntry files are removed in favor of LomoList exit animation contracts.
 *
 * Excludes:
 * - Compose rendering, actual delete persistence, and multi-item batch animation coordination.
 *
 * Test Change Justification:
 * - Reason category: App layer restructuring replaced page-based memo retention and viewport delete animations with LomoList system.
 * - Old behavior/assertion being replaced: previous delete animation tests relied on DeleteAnimationVisualPolicy and DeleteViewportEntry components.
 * - Why old assertion is no longer correct: DeleteAnimationVisualPolicy and DeleteViewportEntry files are removed; delete animations now use LomoList exit animation registry.
 * - Coverage preserved by: all delete-rollback scenarios retained with ExitAnimationRegistry assertions.
 * - Why this is not fitting the test to the implementation: tests verify observable animation registry state changes, not internal Compose frame mechanics.
 */

import com.lomo.app.testing.AppFunSpec
import com.lomo.ui.component.common.ExitAnimationRegistry
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DeleteAnimationRunnerTest : AppFunSpec() {
    init {
        test("marks deleting on success and retains registry entries") {
            runTest {
                val registry = ExitAnimationRegistry<String>()

                val result = runCatching {
                    runDeleteAnimationWithRollback(
                        itemId = "memo_1",
                        registry = registry,
                        item = "snapshot_1",
                        anchoredAfterKey = "anchor_1",
                    ) {
                        Unit
                    }
                }

                result.isSuccess shouldBe true
                registry.entries.value shouldBe mapOf(
                    "memo_1" to ExitAnimationRegistry.ExitEntry("snapshot_1", "anchor_1")
                )
            }
        }

        test("rolls back deleting marker on failure") {
            runTest {
                val registry = ExitAnimationRegistry<String>()

                val result = runCatching {
                    runDeleteAnimationWithRollback(
                        itemId = "memo_1",
                        registry = registry,
                        item = "snapshot_1",
                        anchoredAfterKey = "anchor_1",
                    ) {
                        throw IllegalStateException("delete failed")
                    }
                }

                result.isFailure shouldBe true
                registry.entries.value.isEmpty() shouldBe true
            }
        }

        test("rolls back deleting marker on cancellation") {
            runTest {
                val registry = ExitAnimationRegistry<String>()

                var cancelled = false
                try {
                    runDeleteAnimationWithRollback(
                        itemId = "memo_1",
                        registry = registry,
                        item = "snapshot_1",
                        anchoredAfterKey = "anchor_1",
                    ) {
                        throw CancellationException("cancel")
                    }
                } catch (_: CancellationException) {
                    cancelled = true
                }

                cancelled shouldBe true
                registry.entries.value.isEmpty() shouldBe true
            }
        }

        test("marks all deleting ids on bulk success") {
            runTest {
                val registry = ExitAnimationRegistry<String>()

                val result = runCatching {
                    runDeleteAnimationWithRollback(
                        items = listOf(
                            Triple("memo_1", "snapshot_1", "anchor_1"),
                            Triple("memo_2", "snapshot_2", "anchor_2")
                        ),
                        registry = registry,
                    ) {
                        Unit
                    }
                }

                result.isSuccess shouldBe true
                registry.entries.value shouldBe mapOf(
                    "memo_1" to ExitAnimationRegistry.ExitEntry("snapshot_1", "anchor_1"),
                    "memo_2" to ExitAnimationRegistry.ExitEntry("snapshot_2", "anchor_2")
                )
            }
        }

        test("rolls back all deleting ids on bulk failure") {
            runTest {
                val registry = ExitAnimationRegistry<String>()

                val result = runCatching {
                    runDeleteAnimationWithRollback(
                        items = listOf(
                            Triple("memo_1", "snapshot_1", "anchor_1"),
                            Triple("memo_2", "snapshot_2", "anchor_2")
                        ),
                        registry = registry,
                    ) {
                        throw IllegalStateException("bulk delete failed")
                    }
                }

                result.isFailure shouldBe true
                registry.entries.value.isEmpty() shouldBe true
            }
        }

        test("executes mutation immediately without internal delay") {
            runTest {
                val registry = ExitAnimationRegistry<String>()
                var mutationCalled = false

                runDeleteAnimationWithRollback(
                    itemId = "memo_1",
                    registry = registry,
                    item = "snapshot_1",
                    anchoredAfterKey = "anchor_1",
                ) {
                    mutationCalled = true
                }

                mutationCalled shouldBe true
                registry.entries.value shouldBe mapOf(
                    "memo_1" to ExitAnimationRegistry.ExitEntry("snapshot_1", "anchor_1")
                )
            }
        }
    }
}

