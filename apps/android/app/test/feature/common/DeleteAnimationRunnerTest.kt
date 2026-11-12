/*
 * Dependency upgrade Test Change Justification:
 * Reason category: mechanical migration.
 * Old behavior/assertion being replaced: explicit Unit expressions in success callbacks.
 * Why old assertion is no longer correct: Kotlin 2.4 diagnoses these expressions as unused.
 * Coverage preserved by: the same success callbacks, side effects, state and expected values.
 * Why this is not fitting the test to the implementation: only redundant expression syntax
 * changes; failure and success assertions remain unchanged.
 */
package com.lomo.app.feature.common

/*
 * Behavior Contract:
 * - Unit under test: runDeleteAnimationWithRollback
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: run a delete action with animated exit markers in a registry, rolling back the marker on failure or cancellation.
 *
 * Scenarios:
 * - Given an item id and registry, when the delete action succeeds, then the deleting marker is retained and marked mutation-committed in the registry.
 * - Given an item id and registry, when the delete action throws, then the deleting marker is rolled back.
 * - Given an item id and registry, when the delete action is cancelled, then the deleting marker is rolled back.
 *
 * Observable outcomes:
 * - ExitAnimationRegistry entry presence and result success/failure.
 *
 * TDD proof:
 * - Fails before the fix because successful mutation completion is not modeled separately from
 *   animation settlement and source absence, so a row can be released too early.
 *
 * Test Change Justification:
 * Reason category: requested removal of bulk row animations.
 * Old behavior/assertion being replaced: multi-row animation success and rollback through the bulk overload.
 * Why old assertion is no longer correct: collection clear no longer uses row animations; the unused overload is deleted.
 * Coverage preserved by: item success/failure/cancellation checks and TrashViewModelTest bulk clear checks.
 * Why this is not fitting the test to the implementation: collection clear is now independent of row snapshots.
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
        test("marks deleting on success and records mutation commit without removing registry entries") {
            runTest {
                val registry = ExitAnimationRegistry<String>()

                val result = runCatching {
                    runDeleteAnimationWithRollback(
                        itemId = "memo_1",
                        registry = registry,
                        item = "snapshot_1",
                        anchoredAfterKey = "anchor_1",
                    ) {}
                }

                result.isSuccess shouldBe true
                registry.entries.value shouldBe mapOf(
                    "memo_1" to ExitAnimationRegistry.ExitEntry(
                        item = "snapshot_1",
                        anchoredAfterKey = "anchor_1",
                        animationSettled = false,
                        mutationCommitted = true,
                        sourceAbsent = false,
                    ),
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
                    "memo_1" to ExitAnimationRegistry.ExitEntry(
                        item = "snapshot_1",
                        anchoredAfterKey = "anchor_1",
                        animationSettled = false,
                        mutationCommitted = true,
                        sourceAbsent = false,
                    ),
                )
            }
        }
    }
}
