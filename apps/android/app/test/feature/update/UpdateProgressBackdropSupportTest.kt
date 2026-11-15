package com.lomo.app.feature.update

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Scenarios:
 * - Given Android 13 or newer and a successful shader probe, when backdrop support is resolved, then shader
 *   mode is selected.
 * - Given an older SDK or failed shader probe, when support is resolved, then the supported fallback is
 *   selected.
 * - Unit under test: resolveUpdateProgressBackdropMode
 * - Capability: shader-backed update-progress background selection degrades safely when runtime shader support is unavailable or initialization fails.
 * - Observable outcomes: returned UpdateProgressBackdropMode value and whether the shader probe executes.
 * - TDD proof: Fails before the fix because update-progress backdrop selection is embedded in Compose rendering with no safe fallback contract when shader setup throws.
 * - Excludes: Compose drawing, AGSL correctness, and APK download or installer behavior.
 *
 * Test Change Justification:
 * Reason category: Mechanical formatting and import alignment for UpdateProgressBackdropSupport.
 * Old behavior/assertion being replaced: None; existing scenario bodies and expected results are retained.
 * Why old assertion is no longer correct: Existing assertions remain correct. Layout, imports, and equivalent
 * fixture declaration syntax do not change their behavior.
 * Coverage preserved by: The existing scenarios, including returns shader mode on android 13 and above when
 * shader probe succeeds; falls back when shader probe throws.
 * Why this is not fitting the test to the implementation: No expected result is relaxed or replaced; the
 * original behavior locks remain executable.
 */
class UpdateProgressBackdropSupportTest : AppFunSpec() {
    init {
        test("returns shader mode on android 13 and above when shader probe succeeds") {
            var probeCalled = false

            val mode =
                resolveUpdateProgressBackdropMode(sdkInt = 33) {
                    probeCalled = true
                }

            (mode) shouldBe (UpdateProgressBackdropMode.Shader)
            ((probeCalled)) shouldBe true
        }
    }

    init {
        test("falls back when shader probe throws") {
            val mode =
                resolveUpdateProgressBackdropMode(sdkInt = 33) {
                    error("shader compile failed")
                }

            (mode) shouldBe (UpdateProgressBackdropMode.Fallback)
        }
    }

    init {
        test("falls back below android 13 without probing shader support") {
            var probeCalled = false

            val mode =
                resolveUpdateProgressBackdropMode(sdkInt = 32) {
                    probeCalled = true
                }

            (mode) shouldBe (UpdateProgressBackdropMode.Fallback)
            ((probeCalled)) shouldBe false
        }
    }

    init {
        test("falls back on sdk 36 without probing shader support") {
            var probeCalled = false

            val mode =
                resolveUpdateProgressBackdropMode(sdkInt = 36) {
                    probeCalled = true
                }

            (mode) shouldBe (UpdateProgressBackdropMode.Fallback)
            ((probeCalled)) shouldBe false
        }
    }
}
