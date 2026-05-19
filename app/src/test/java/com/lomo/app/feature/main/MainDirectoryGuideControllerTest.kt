/*
 * Behavior Contract:
 * - Unit under test: MainDirectoryGuideController
 * - Owning layer: app
 * - Priority tier: P2
 * - Capability: manage guide types during directory setup flow.
 *
 * Scenarios:
 * - Given a directory guide controller, when requesting image setup, then setup type is updated to DirectorySetupType.Image.
 * - Given a controller with active setup type, when cleared, then setup type is reset to null.
 *
 * Observable outcomes:
 * - changes to setupType state.
 *
 * TDD proof:
 * - Compilation failure on Kotest transition - test-only migration; no production change.
 *
 * Excludes:
 * - view components, lifecycle wiring.
 */

package com.lomo.app.feature.main

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

class MainDirectoryGuideControllerTest : AppFunSpec() {
    init {
        test("requestImage sets image guide type") {
            val controller = MainDirectoryGuideController()

            controller.requestImage()

            (controller.setupType) shouldBe (DirectorySetupType.Image)
        }

        test("requestVoice then clear resets guide type") {
            val controller = MainDirectoryGuideController()
            controller.requestVoice()

            controller.clear()

            (controller.setupType) shouldBe null
        }
    }
}
