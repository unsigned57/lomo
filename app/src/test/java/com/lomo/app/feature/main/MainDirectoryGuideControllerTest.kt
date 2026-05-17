/*
 * Test Contract:
 * - Unit under test: MainDirectoryGuideControllerTest
 * - Owning layer: app
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for MainDirectoryGuideControllerTest.
 * - Boundary: boundary and edge cases for MainDirectoryGuideControllerTest.
 * - Failure: failure and error scenarios for MainDirectoryGuideControllerTest.
 * - Must-not-happen: invariants are never violated for MainDirectoryGuideControllerTest.
 *
 * - Behavior focus: test behavioral outcomes of MainDirectoryGuideControllerTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

class MainDirectoryGuideControllerTest : AppFunSpec() {
    init {
        test("requestImage sets image guide type") {
            val controller = MainDirectoryGuideController()

            controller.requestImage()

            (controller.setupType) shouldBe (DirectorySetupType.Image)
        }
    }

    init {
        test("requestVoice then clear resets guide type") {
            val controller = MainDirectoryGuideController()
            controller.requestVoice()

            controller.clear()

            (controller.setupType) shouldBe null
        }
    }

}
