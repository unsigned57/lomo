package com.lomo.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: buildShareCardFooterContent
 * - Behavior focus: share-card footer single-line composition, custom signature rendering, and Lomo fallback after recorded-days removal.
 * - Observable outcomes: ShareCardFooterContent row structure and footer visibility used by footer rendering.
 * - Red phase: Fails before the fix because footer composition still accepts recorded-days input and builds a two-row footer instead of a single-line time/signature footer.
 * - Test Change Justification: reason category = product contract changed; old assertions expected recorded-days gating and a two-row layout, which is no longer correct after the product requirement changed to remove recorded days and keep only one footer line. Coverage is preserved by asserting the new single-line time/signature layout plus footer-hidden and blank-signature fallback paths. This is not changing the test to fit the implementation because the user explicitly changed the footer contract.
 * - Excludes: canvas drawing, bitmap creation, and Android resource lookup.
 */
class ShareCardBitmapRendererFooterTest {
    @Test
    fun `buildShareCardFooterContent keeps time and signature on a single footer row when both are enabled`() {
        val result =
            buildShareCardFooterContent(
                showTime = true,
                showSignature = true,
                signatureText = "Unsigned57",
                createdAtText = "2026-04-15 09:30",
            )

        assertEquals(
            ShareCardFooterContent(
                showFooter = true,
                row =
                    ShareCardFooterRow(
                        startText = "2026-04-15 09:30",
                        endText = "Unsigned57",
                    ),
            ),
            result,
        )
    }

    @Test
    fun `buildShareCardFooterContent falls back blank signature to Lomo and keeps signature on bottom row`() {
        val result =
            buildShareCardFooterContent(
                showTime = false,
                showSignature = true,
                signatureText = "   ",
                createdAtText = "2026-04-15 09:30",
            )

        assertEquals(
            ShareCardFooterContent(
                showFooter = true,
                row =
                    ShareCardFooterRow(
                        centerText = "Lomo",
                    ),
            ),
            result,
        )
    }

    @Test
    fun `buildShareCardFooterContent hides footer when all footer values are disabled or blank`() {
        val result =
            buildShareCardFooterContent(
                showTime = false,
                showSignature = false,
                signatureText = "Unsigned57",
                createdAtText = "2026-04-15 09:30",
            )

        assertEquals(
            ShareCardFooterContent(
                showFooter = false,
                row = null,
            ),
            result,
        )
    }
}
