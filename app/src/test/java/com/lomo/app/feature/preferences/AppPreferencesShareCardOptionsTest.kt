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


import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: observeAppPreferences state aggregation.
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: aggregates share-card signature text preference into shared app preferences.
 *
 * Scenarios:
 * - Given configured signature text, when observeAppPreferences is observed, then include signature text in the state.
 *
 * Observable outcomes:
 * - Emitted AppPreferencesState field for signature text matches the fake settings.
 *
 * TDD proof:
 * - Compilation failure on Kotest transition - test-only migration; no production change.
 *
 * Excludes:
 * - Compose rendering, repository persistence internals, and share-card bitmap drawing.
 */
class AppPreferencesShareCardOptionsTest : AppFunSpec() {
    private val appConfigRepository = FakeAppConfigRepository()

    init {
        test("observeAppPreferences includes share card signature text") {
            runTest {
                appConfigRepository.setShareCardSignatureText("Unsigned57")

                val state = appConfigRepository.observeAppPreferences().first()

                state.shareCardSignatureText shouldBe "Unsigned57"
            }
        }
    }
}
