package com.lomo.app.feature.main

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: main memo filter color policy.
 * - Owning layer: app
 * - Priority tier: P2
 * - Capability: date-range filter controls derive their leading icon and date button colors
 *   from one Material color scheme policy without changing the sheet background.
 *
 * Scenarios:
 * - Given the date range has a value, when the leading icon colors are resolved, then primary
 *   container roles are used.
 * - Given the date range is empty, when the leading icon colors are resolved, then neutral
 *   surface roles are used.
 * - Given a date button has a value, when colors are resolved, then the button container and
 *   content use primary container roles.
 * - Given a date button is empty, when colors are resolved, then the button keeps the neutral
 *   surface treatment.
 *
 * Observable outcomes:
 * - Returned leading icon, button container, text, icon, and clear-action colors.
 *
 * TDD proof:
 * - RED before implementation because the date-range leading icon had no policy owner and filled
 *   date buttons still resolved to the neutral surface container instead of the active palette role.
 *
 * Excludes:
 * - Sheet container/background color, content-flag filters, Compose rendering, date picking behavior,
 *   haptics, and layout measurement.
 *
 * Test Change Justification:
 * - Reason category: scope correction after user clarification.
 * - Old behavior/assertion being replaced: content-flag tile color assertions were included in this policy test.
 * - Why old assertion is no longer correct: the reported issue is only the date-range leading icon
 *   and the two date buttons; content-flag tile backgrounds were not part of the bug.
 * - Coverage preserved by: existing UI behavior plus focused date-range color policy assertions.
 * - Why this is not fitting the test to the implementation: the new assertions describe the clarified
 *   user-visible target controls and exclude the sheet/background explicitly.
 */
class MainMemoFilterColorPolicyTest : AppFunSpec() {
    init {
        test("date range leading icon uses active and neutral palette roles") {
            val scheme = sampleColorScheme()

            mainMemoDateRangeIconColors(isActive = true, colorScheme = scheme) shouldBe
                MainMemoDateRangeIconColors(
                    containerColor = scheme.primaryContainer,
                    iconColor = scheme.onPrimaryContainer,
                )
            mainMemoDateRangeIconColors(isActive = false, colorScheme = scheme) shouldBe
                MainMemoDateRangeIconColors(
                    containerColor = scheme.surfaceContainerHigh,
                    iconColor = scheme.onSurfaceVariant,
                )
        }

        test("date buttons use active palette roles only when they hold a value") {
            val scheme = sampleColorScheme()

            mainMemoDateFieldColors(hasValue = true, colorScheme = scheme) shouldBe
                MainMemoDateFieldColors(
                    containerColor = scheme.primaryContainer,
                    iconColor = scheme.onPrimaryContainer,
                    clearActionColor = scheme.onPrimaryContainer,
                    labelColor = scheme.onPrimaryContainer,
                    valueColor = scheme.onPrimaryContainer,
                )
            mainMemoDateFieldColors(hasValue = false, colorScheme = scheme) shouldBe
                MainMemoDateFieldColors(
                    containerColor = scheme.surfaceContainerLow,
                    iconColor = scheme.onSurfaceVariant,
                    clearActionColor = scheme.onSurfaceVariant,
                    labelColor = scheme.onSurfaceVariant,
                    valueColor = scheme.onSurface,
                )
        }
    }

    private fun sampleColorScheme() =
        lightColorScheme(
            primary = Color(0xFF005EB8),
            primaryContainer = Color(0xFFD7E3FF),
            onPrimaryContainer = Color(0xFF001B3F),
            errorContainer = Color(0xFFFFDAD6),
            onErrorContainer = Color(0xFF410002),
            surfaceContainerLow = Color(0xFFF7F2FA),
            onSurface = Color(0xFF1C1B1F),
            onSurfaceVariant = Color(0xFF49454F),
        )
}
