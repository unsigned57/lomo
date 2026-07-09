package com.lomo.app.util

import com.lomo.app.testing.AppFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: requireSuccessfulPngEncode
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: share-image PNG writers surface bitmap encoder failure instead of returning invalid cache paths.
 *
 * Scenarios:
 * - Given Android reports a successful Bitmap.compress result, when the policy is checked, then no error is thrown.
 * - Given Android reports a failed Bitmap.compress result, when the policy is checked, then persistence is aborted with an explicit error.
 *
 * Observable outcomes:
 * - Returned Unit for success and thrown IllegalStateException message for failure.
 *
 * TDD proof:
 * - RED: fails before the follow-up because no shared PNG encode policy exists and production writers ignore the Bitmap.compress Boolean.
 *
 * Excludes:
 * - Android Bitmap implementation, actual PNG bytes, FileProvider URI creation, and share sheet dispatch.
 */
class ShareImagePngEncodingPolicyTest : AppFunSpec() {
    init {
        test("given successful png encode when policy is checked then no error is thrown") {
            requireSuccessfulPngEncode(encoded = true)
        }

        test("given failed png encode when policy is checked then explicit error is thrown") {
            val error =
                shouldThrow<IllegalStateException> {
                    requireSuccessfulPngEncode(encoded = false)
                }

            error.message shouldBe "Failed to encode share image as PNG"
        }
    }
}
