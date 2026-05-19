package com.lomo.app.feature.common

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.ui.component.menu.MemoActionId
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: AppConfigUiCoordinator memo action auto-reordering.
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: promotes or retains memo actions based on usage when enabled/disabled.
 *
 * Scenarios:
 * - Given existing memo action history and auto-reorder enabled, when an action is used, then promote it one position.
 * - Given empty action history and auto-reorder enabled, when an action is used, then seed default order and nudge the action.
 * - Given auto-reorder disabled, when an action is used, then skip ordering updates.
 *
 * Observable outcomes:
 * - Persisted memo action order state matches the expected Heat-style promotion rules.
 *
 * TDD proof:
 * - Compilation failure on Kotest transition - test-only migration; no production change.
 *
 * Excludes:
 * - Compose rendering, DataStore serialization details, and settings screen wiring.
 */
class AppConfigUiCoordinatorMemoActionUsageTest : AppFunSpec() {
    private val appConfigRepository = FakeAppConfigRepository()

    init {
        test("recordMemoActionUsage promotes the selected action only one position when history already exists") {
            runTest {
                appConfigRepository.setMemoActionAutoReorderEnabled(true)
                appConfigRepository.setMemoActionOrder(
                    order = listOf(
                        MemoActionId.COPY.storageKey,
                        MemoActionId.HISTORY.storageKey,
                        MemoActionId.EDIT.storageKey,
                    )
                )

                AppConfigUiCoordinator(appConfigRepository).recordMemoActionUsage(MemoActionId.EDIT.storageKey)

                val resultOrder = appConfigRepository.getMemoActionOrder().first()
                resultOrder shouldBe listOf(
                    MemoActionId.COPY.storageKey,
                    MemoActionId.EDIT.storageKey,
                    MemoActionId.HISTORY.storageKey,
                    MemoActionId.SHARE_IMAGE.storageKey,
                    MemoActionId.SHARE_TEXT.storageKey,
                    MemoActionId.LAN_SHARE.storageKey,
                    MemoActionId.PIN.storageKey,
                    MemoActionId.JUMP.storageKey,
                    MemoActionId.DELETE.storageKey,
                )
            }
        }

        test("recordMemoActionUsage seeds default order and nudges the selected action when no history exists") {
            runTest {
                appConfigRepository.setMemoActionAutoReorderEnabled(true)
                appConfigRepository.setMemoActionOrder(order = emptyList())

                AppConfigUiCoordinator(appConfigRepository).recordMemoActionUsage(MemoActionId.DELETE.storageKey)

                val resultOrder = appConfigRepository.getMemoActionOrder().first()
                resultOrder shouldBe listOf(
                    MemoActionId.COPY.storageKey,
                    MemoActionId.SHARE_IMAGE.storageKey,
                    MemoActionId.SHARE_TEXT.storageKey,
                    MemoActionId.LAN_SHARE.storageKey,
                    MemoActionId.PIN.storageKey,
                    MemoActionId.JUMP.storageKey,
                    MemoActionId.HISTORY.storageKey,
                    MemoActionId.DELETE.storageKey,
                    MemoActionId.EDIT.storageKey,
                )
            }
        }

        test("recordMemoActionUsage skips writes when auto reorder is disabled") {
            runTest {
                appConfigRepository.setMemoActionAutoReorderEnabled(false)
                val initialOrder = listOf(
                    MemoActionId.COPY.storageKey,
                    MemoActionId.HISTORY.storageKey,
                    MemoActionId.EDIT.storageKey,
                )
                appConfigRepository.setMemoActionOrder(order = initialOrder)

                AppConfigUiCoordinator(appConfigRepository).recordMemoActionUsage(MemoActionId.HISTORY.storageKey)

                val resultOrder = appConfigRepository.getMemoActionOrder().first()
                resultOrder shouldBe initialOrder
            }
        }
    }
}
