package com.lomo.app.feature.preferences

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


import com.lomo.app.feature.common.MemoActionOrderScopes
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.app.testing.fakes.FakeCustomFontStore
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: observeAppPreferences in AppPreferencesState.kt
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: aggregates memo action auto-reorder and list preferences into the shared app-preferences state.
 *
 * Scenarios:
 * - Given configured auto-reorder and scope ordering preferences, when observeAppPreferences is observed, then include those action preferences in the emitted state.
 *
 * Observable outcomes:
 * - Emitted AppPreferencesState snapshot fields for reorder and scoped list orderings match the fake settings.
 *
 * TDD proof:
 * - Compilation failure on Kotest transition - test-only migration; no production change.
 *
 * Excludes:
 * - screen-specific menu rendering, DataStore serialization, and unrelated settings groups.
 */
class AppPreferencesMemoActionStateTest : AppFunSpec() {
    private val appConfigRepository = FakeAppConfigRepository()

    init {
        test("observeAppPreferences includes memo action ordering preferences") {
            runTest {
                appConfigRepository.setMemoActionAutoReorderEnabled(true)
                appConfigRepository.setMemoActionOrder(order = listOf("history", "copy"))
                appConfigRepository.setMemoActionOrder(
                    scope = MemoActionOrderScopes.GALLERY,
                    order = listOf("jump", "copy")
                )
                appConfigRepository.updateInputToolbarToolOrder(listOf("backfill", "camera"))

                val state = appConfigRepository.observeAppPreferences(FakeCustomFontStore()).first()

                state.memoActionAutoReorderEnabled shouldBe true
                state.memoActionOrder shouldBe listOf("history", "copy")
                state.memoActionOrderFor(MemoActionOrderScopes.GALLERY) shouldBe listOf("jump", "copy")
                state.inputToolbarToolOrder shouldBe listOf("backfill", "camera")
            }
        }
    }
}
