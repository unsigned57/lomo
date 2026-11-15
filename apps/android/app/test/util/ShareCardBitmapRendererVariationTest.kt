package com.lomo.app.util

import android.graphics.Typeface
import com.lomo.app.feature.preferences.ShareCardTypefaces
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk

/*
 * Behavior Contract:
 * - Production scope: apps/android/app/src/util/ShareCardBitmapRendererText.kt
 * - Production scope: apps/android/app/src/util/ShareCardBitmapRendererRenderLines.kt
 * - Production scope: apps/android/app/src/util/ShareCardBitmapRenderer.kt
 * - Production scope: apps/android/app/src/util/ShareUtils.kt
 * - Unit under test: ShareCard inline variation typeface run resolver and header typeface selector.
 * - Owning layer: app util/share card bitmap renderer.
 * - Priority tier: P1.
 * - Capability: apply variable-font regular, bold, italic, and boldItalic Typefaces cleanly
 *   across share-card headings/tags/bullets and inline markdown spans (including overlapping
 *   bold+italic intervals) while falling back to standard platform styles when axes are absent.
 *
 * Scenarios:
 * - Given non-overlapping and overlapping bold/italic ShareInlineStyleRanges with full
 *   ShareCardTypefaces, when resolved, then exact variable bold/italic/boldItalic typefaces are
 *   selected per interval.
 * - Given a wght-only variable font (italic and boldItalic typefaces null), when bold+italic
 *   ranges overlap, then custom bold typeface is paired with platform Typeface.ITALIC fallback.
 * - Given full vs null ShareCardTypefaces, when resolving bold paint typeface for headers/tags,
 *   then dedicated variable bold typeface is chosen over base regular typeface.
 *
 * Observable outcomes: ordered ShareCardInlineFontRun list and resolved ShareCardBoldTypeface choice.
 *
 * TDD proof:
 * - RED: `./kotlin test --include-module=app --include-classes=com.lomo.app.util.ShareCardBitmapRendererVariationTest`
 *   fails compilation until resolveShareCardInlineFontRuns and resolveShareCardEmphasizedTypeface
 *   exist in the ShareCard renderer.
 * - GREEN: same command passes with all assertions green.
 *
 * Excludes: Android Canvas/StaticLayout bitmap rasterization and Toast/FileProvider sharing.
 */
class ShareCardBitmapRendererVariationTest : AppFunSpec() {
    init {
        test(
            "given overlapping bold and italic ranges with full variable typefaces then intervals map to bold boldItalic and italic",
        ) {
            val regular = mockk<Typeface>()
            val bold = mockk<Typeface>()
            val italic = mockk<Typeface>()
            val boldItalic = mockk<Typeface>()
            val typefaces =
                ShareCardTypefaces(
                    regular = regular,
                    bold = bold,
                    italic = italic,
                    boldItalic = boldItalic,
                )
            val ranges =
                listOf(
                    ShareInlineStyleRange(start = 0, end = 6, kind = ShareInlineStyleKind.Bold),
                    ShareInlineStyleRange(start = 4, end = 10, kind = ShareInlineStyleKind.Italic),
                    ShareInlineStyleRange(start = 0, end = 10, kind = ShareInlineStyleKind.Underline),
                )

            val runs = resolveShareCardInlineFontRuns(textLength = 10, inlineStyles = ranges, bodyTypefaces = typefaces)

            runs shouldContainExactly
                listOf(
                    ShareCardInlineFontRun(start = 0, end = 4, typeface = bold, fallbackStyle = null),
                    ShareCardInlineFontRun(start = 4, end = 6, typeface = boldItalic, fallbackStyle = null),
                    ShareCardInlineFontRun(start = 6, end = 10, typeface = italic, fallbackStyle = null),
                )
        }

        test(
            "given a wght-only variable font without ital axis then overlapping bold+italic pairs custom bold with platform italic style",
        ) {
            val regular = mockk<Typeface>()
            val bold = mockk<Typeface>()
            val typefaces =
                ShareCardTypefaces(
                    regular = regular,
                    bold = bold,
                    italic = null,
                    boldItalic = null,
                )
            val ranges =
                listOf(
                    ShareInlineStyleRange(start = 0, end = 5, kind = ShareInlineStyleKind.Bold),
                    ShareInlineStyleRange(start = 2, end = 7, kind = ShareInlineStyleKind.Italic),
                )

            val runs = resolveShareCardInlineFontRuns(textLength = 7, inlineStyles = ranges, bodyTypefaces = typefaces)

            runs shouldContainExactly
                listOf(
                    ShareCardInlineFontRun(start = 0, end = 2, typeface = bold, fallbackStyle = null),
                    ShareCardInlineFontRun(start = 2, end = 5, typeface = bold, fallbackStyle = Typeface.ITALIC),
                    ShareCardInlineFontRun(start = 5, end = 7, typeface = null, fallbackStyle = Typeface.ITALIC),
                )
        }

        test("given static custom font with no variable axes then spans fall back to platform style spans cleanly") {
            val regular = mockk<Typeface>()
            val typefaces = ShareCardTypefaces(regular = regular)
            val ranges =
                listOf(
                    ShareInlineStyleRange(start = 0, end = 3, kind = ShareInlineStyleKind.Bold),
                    ShareInlineStyleRange(start = 2, end = 5, kind = ShareInlineStyleKind.Italic),
                )

            val runs = resolveShareCardInlineFontRuns(textLength = 5, inlineStyles = ranges, bodyTypefaces = typefaces)

            runs shouldContainExactly
                listOf(
                    ShareCardInlineFontRun(start = 0, end = 2, typeface = null, fallbackStyle = Typeface.BOLD),
                    ShareCardInlineFontRun(start = 2, end = 3, typeface = null, fallbackStyle = Typeface.BOLD_ITALIC),
                    ShareCardInlineFontRun(start = 3, end = 5, typeface = null, fallbackStyle = Typeface.ITALIC),
                )
        }

        test("given ShareCardTypefaces then selectPreferredBoldTypeface returns dedicated variable bold when present") {
            val regular = mockk<Typeface>()
            val bold = mockk<Typeface>()
            val variableTypefaces = ShareCardTypefaces(regular = regular, bold = bold)
            val staticTypefaces = ShareCardTypefaces(regular = regular)

            selectPreferredBoldTypeface(variableTypefaces) shouldBe bold
            selectPreferredBoldTypeface(staticTypefaces) shouldBe null
            selectPreferredBoldTypeface(null) shouldBe null
        }
    }
}
