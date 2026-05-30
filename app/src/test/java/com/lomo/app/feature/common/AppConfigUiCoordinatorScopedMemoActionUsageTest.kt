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
import com.lomo.app.feature.memo.MemoActionId
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: AppConfigUiCoordinator scoped memo-action ordering.
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: coordinates separate memo action lists for different page scopes (like GALLERY, REVIEW, etc.).
 *
 * Scenarios:
 * - Given a specific screen scope, when recordMemoActionUsage is called, then only promote that requested surface order.
 * - Given a specific screen scope, when manual reorder is triggered, then only write to that requested surface order.
 *
 * Observable outcomes:
 * - Persisted state for a specific scope is updated correctly, while other scopes remain unchanged.
 *
 * TDD proof:
 * - Compilation failure on Kotest transition - test-only migration; no production change.
 *
 * Excludes:
 * - Compose menu rendering, DataStore serialization, and drag gesture detection.
 */
class AppConfigUiCoordinatorScopedMemoActionUsageTest : AppFunSpec() {
    private val appConfigRepository = FakeAppConfigRepository()

    init {
        test("recordMemoActionUsage promotes only the requested surface order") {
            runTest {
                appConfigRepository.setMemoActionAutoReorderEnabled(true)
                appConfigRepository.setMemoActionOrder(
                    scope = MemoActionOrderScopes.GALLERY,
                    order = listOf(
                        MemoActionId.COPY.storageKey,
                        MemoActionId.JUMP.storageKey,
                        MemoActionId.EDIT.storageKey,
                    )
                )

                AppConfigUiCoordinator(appConfigRepository, com.lomo.app.testing.fakes.FakeCustomFontStore()).recordMemoActionUsage(
                    scope = MemoActionOrderScopes.GALLERY,
                    actionId = MemoActionId.EDIT.storageKey,
                )

                val galleryOrder = appConfigRepository.getMemoActionOrder(MemoActionOrderScopes.GALLERY).first()
                galleryOrder shouldBe listOf(
                    MemoActionId.COPY.storageKey,
                    MemoActionId.EDIT.storageKey,
                    MemoActionId.JUMP.storageKey,
                    MemoActionId.SHARE_IMAGE.storageKey,
                    MemoActionId.SHARE_TEXT.storageKey,
                    MemoActionId.LAN_SHARE.storageKey,
                    MemoActionId.PIN.storageKey,
                    MemoActionId.HISTORY.storageKey,
                    MemoActionId.DELETE.storageKey,
                )

                // Verify other scopes (like default/main order) were not written to.
                val defaultOrder = appConfigRepository.getMemoActionOrder().first()
                defaultOrder shouldBe emptyList()
            }
        }

        test("manual reorder writes only the requested surface order") {
            runTest {
                val order = listOf(MemoActionId.JUMP.storageKey, MemoActionId.COPY.storageKey)

                AppConfigUiCoordinator(appConfigRepository, com.lomo.app.testing.fakes.FakeCustomFontStore()).updateMemoActionOrder(
                    scope = MemoActionOrderScopes.REVIEW,
                    order = order,
                )

                val reviewOrder = appConfigRepository.getMemoActionOrder(MemoActionOrderScopes.REVIEW).first()
                reviewOrder shouldBe order

                // Verify other scopes (like default/main order) were not written to.
                val defaultOrder = appConfigRepository.getMemoActionOrder().first()
                defaultOrder shouldBe emptyList()
            }
        }
    }
}
