/*
 * Behavior Contract:
 * - Unit under test: sync inbox settings section policy.
 * - Owning layer: app
 * - Priority tier: P2
 * - Capability: determine visibility and interactivity of sync inbox settings rows based on enablement state.
 *
 * Scenarios:
 * - Given sync inbox is disabled, when resolving section policy, then header is visible and interactive, but directory preference is hidden.
 * - Given sync inbox is enabled, when resolving section policy, then header is visible and interactive, and directory preference is visible.
 *
 * Observable outcomes:
 * - SyncInboxSectionPolicies state booleans.
 *
 * TDD proof:
 * - Compilation failure on Kotest transition - test-only migration; no production change.
 *
 * Excludes:
 * - Compose animations, picker launching, and repository persistence.
 */

package com.lomo.app.feature.settings

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
import io.kotest.matchers.shouldBe

class SettingsSyncInboxSectionPolicyTest : AppFunSpec() {
    init {
        test("collapsed section stays interactive when sync inbox is disabled") {
            val policy = SyncInboxSectionPolicies.resolve(enabled = false)

            ((policy.showSectionHeader)) shouldBe true
            ((policy.headerInteractive)) shouldBe true
            ((policy.showDirectoryPreference)) shouldBe false
        }

        test("expanded section shows directory preference when sync inbox is enabled") {
            val policy = SyncInboxSectionPolicies.resolve(enabled = true)

            ((policy.showSectionHeader)) shouldBe true
            ((policy.headerInteractive)) shouldBe true
            ((policy.showDirectoryPreference)) shouldBe true
        }
    }
}
