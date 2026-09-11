package com.lomo.app.feature.update

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: resolveUpdateProgressBackdropMode
 * - Behavior focus: shader-backed update-progress background selection degrades safely when runtime shader support is unavailable or initialization fails.
 * - Observable outcomes: returned UpdateProgressBackdropMode value and whether the shader probe executes.
 * - Red phase: Fails before the fix because update-progress backdrop selection is embedded in Compose rendering with no safe fallback contract when shader setup throws.
 * - Excludes: Compose drawing, AGSL correctness, and APK download or installer behavior.
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
