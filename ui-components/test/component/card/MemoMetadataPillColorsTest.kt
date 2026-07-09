package com.lomo.ui.component.card

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: Memo metadata pill color policy.
 * - Owning layer: ui-components
 * - Priority tier: P2
 * - Capability: memo tag and reminder pills derive from the active Material color scheme instead of
 *   choosing local feature colors.
 *
 * Scenarios:
 * - Given the active color scheme, when tag pill colors are resolved, then secondary roles are used.
 * - Given an active reminder, when reminder pill colors are resolved, then primary roles are used.
 * - Given an exhausted reminder, when reminder pill colors are resolved, then neutral surface roles are used.
 *
 * Observable outcomes:
 * - Returned container/content colors.
 *
 * TDD proof:
 * - RED before implementation because the shared pill color policy does not exist and reminder pills
 *   choose colors locally.
 *
 * Excludes:
 * - Compose rendering, text formatting, click handling, and contrast math.
 */
class MemoMetadataPillColorsTest : UiComponentsFunSpec() {
    init {
        test("tag pills use secondary roles from active color scheme") {
            val scheme = sampleColorScheme()

            memoTagPillColors(scheme) shouldBe
                MemoMetadataPillColors(
                    containerColor = scheme.secondaryContainer,
                    contentColor = scheme.onSecondaryContainer,
                )
        }

        test("active reminder pills use primary roles from active color scheme") {
            val scheme = sampleColorScheme()

            memoReminderPillColors(isExhausted = false, colorScheme = scheme) shouldBe
                MemoMetadataPillColors(
                    containerColor = scheme.primaryContainer,
                    contentColor = scheme.onPrimaryContainer,
                )
        }

        test("exhausted reminder pills use neutral surface roles from active color scheme") {
            val scheme = sampleColorScheme()

            memoReminderPillColors(isExhausted = true, colorScheme = scheme) shouldBe
                MemoMetadataPillColors(
                    containerColor = scheme.surfaceContainerHigh,
                    contentColor = scheme.onSurfaceVariant,
                )
        }
    }

    private fun sampleColorScheme() =
        lightColorScheme(
            primaryContainer = Color(0xFFCCE5FF),
            onPrimaryContainer = Color(0xFF001D34),
            secondaryContainer = Color(0xFFE6DEFF),
            onSecondaryContainer = Color(0xFF1F1735),
            surfaceContainerHigh = Color(0xFFF0F0F0),
            onSurfaceVariant = Color(0xFF46464F),
        )
}
