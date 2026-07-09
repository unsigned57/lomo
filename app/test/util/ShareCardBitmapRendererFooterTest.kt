package com.lomo.app.util

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

/*
 * Behavior Contract:
 * - Unit under test: buildShareCardFooterContent
 * - Behavior focus: share-card footer single-line composition, custom signature rendering, and Lomo fallback after recorded-days removal.
 * - Observable outcomes: ShareCardFooterContent row structure and footer visibility used by footer rendering.
 * - TDD proof: Fails before the fix because footer composition still accepts recorded-days input and builds a two-row footer instead of a single-line time/signature footer.
 * - Test Change Justification: reason category = product contract changed; old assertions expected recorded-days gating and a two-row layout, which is no longer correct after the product requirement changed to remove recorded days and keep only one footer line. Coverage is preserved by asserting the new single-line time/signature layout plus footer-hidden and blank-signature fallback paths. This is not changing the test to fit the implementation because the user explicitly changed the footer contract.
 * - Excludes: canvas drawing, bitmap creation, and Android resource lookup.
 */
class ShareCardBitmapRendererFooterTest : AppFunSpec() {
    init {
        test("buildShareCardFooterContent keeps time and signature on a single footer row when both are enabled") {
            val result =
                buildShareCardFooterContent(
                    showTime = true,
                    showSignature = true,
                    signatureText = "Unsigned57",
                    createdAtText = "2026-04-15 09:30",
                )

            (result) shouldBe (ShareCardFooterContent(
                    showFooter = true,
                    row =
                        ShareCardFooterRow(
                            startText = "2026-04-15 09:30",
                            endText = "Unsigned57",
                        ),
                ))
        }
    }

    init {
        test("buildShareCardFooterContent falls back blank signature to Lomo and keeps signature on bottom row") {
            val result =
                buildShareCardFooterContent(
                    showTime = false,
                    showSignature = true,
                    signatureText = "   ",
                    createdAtText = "2026-04-15 09:30",
                )

            (result) shouldBe (ShareCardFooterContent(
                    showFooter = true,
                    row =
                        ShareCardFooterRow(
                            centerText = "Lomo",
                        ),
                ))
        }
    }

    init {
        test("buildShareCardFooterContent hides footer when all footer values are disabled or blank") {
            val result =
                buildShareCardFooterContent(
                    showTime = false,
                    showSignature = false,
                    signatureText = "Unsigned57",
                    createdAtText = "2026-04-15 09:30",
                )

            (result) shouldBe (ShareCardFooterContent(
                    showFooter = false,
                    row = null,
                ))
        }
    }

}
