package com.lomo.app.util

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: share-card palette policy.
 * - Owning layer: app
 * - Priority tier: P2
 * - Capability: off-composition share-card bitmap rendering uses the same Material color roles as
 *   the in-app theme instead of renderer-local colors.
 *
 * Scenarios:
 * - Given a resolved ColorScheme, when the share-card palette is built, then background, card,
 *   text, tag, divider, quote, and link colors map directly from scheme roles.
 *
 * Observable outcomes:
 * - Returned ShareCardPalette ARGB values.
 *
 * TDD proof:
 * - RED before implementation because share-card palette mapping is private to the renderer instead
 *   of a reusable policy that can be locked independently.
 *
 * Excludes:
 * - Bitmap drawing, text layout, dynamic wallpaper resolution, and Android resource lookup.
 */
class ShareCardPalettePolicyTest : AppFunSpec() {
    init {
        test("share card palette maps directly from active Material color scheme roles") {
            val scheme =
                lightColorScheme(
                    surfaceContainerLowest = Color(0xFFFFFFFF),
                    surface = Color(0xFFFDF8FD),
                    surfaceContainerLow = Color(0xFFF7F2FA),
                    outlineVariant = Color(0xFFCAC4D0),
                    onSurface = Color(0xFF1C1B1F),
                    onSurfaceVariant = Color(0xFF49454F),
                    secondaryContainer = Color(0xFFE8DEF8),
                    onSecondaryContainer = Color(0xFF1D192B),
                    primary = Color(0xFF6750A4),
                )

            shareCardPaletteFromColorScheme(scheme) shouldBe
                ShareCardPalette(
                    bgStart = scheme.surfaceContainerLowest.toArgb(),
                    bgEnd = scheme.surface.toArgb(),
                    card = scheme.surfaceContainerLow.toArgb(),
                    cardBorder = scheme.outlineVariant.toArgb(),
                    bodyText = scheme.onSurface.toArgb(),
                    secondaryText = scheme.onSurfaceVariant.toArgb(),
                    tagBg = scheme.secondaryContainer.toArgb(),
                    tagText = scheme.onSecondaryContainer.toArgb(),
                    divider = scheme.outlineVariant.toArgb(),
                    quoteIndicator = scheme.primary.toArgb(),
                    linkText = scheme.primary.toArgb(),
                )
        }
    }
}
