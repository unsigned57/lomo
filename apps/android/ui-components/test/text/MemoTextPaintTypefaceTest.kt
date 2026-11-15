package com.lomo.ui.text

import android.graphics.Typeface
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.mockk

/*
 * Behavior Contract:
 * - Production scope: apps/android/ui-components/src/text/MemoTextPaint.kt
 * - Production scope: apps/android/ui-components/src/text/MemoTextDrawing.kt
 * - Production scope: apps/android/ui-components/src/text/MemoComposeParagraphText.kt
 * - Unit under test: MemoTextPaint typeface key collection and span typeface lookup.
 * - Owning layer: ui-components text renderer.
 * - Priority tier: P1.
 * - Capability: pre-resolve distinct (FontFamily, FontWeight, FontStyle) combinations from base
 *   TextStyle and AnnotatedString inline spans through Compose FontFamilyResolver so variable-font
 *   bold, italic, and monospace spans preserve full variation settings instead of falling back to
 *   single-weight Typeface.create synthesis.
 *
 * Scenarios:
 * - Given a paragraph with base regular text plus bold, italic, and monospace spans, when
 *   collectMemoSpanTypefaceKeys runs, then each distinct (fontFamily, fontWeight, fontStyle) key
 *   is extracted without duplicates.
 * - Given a span-aware typefaceResolver callback, when resolveMemoTypeface resolves base or
 *   inline-span styles, then the exact Typeface supplied by the resolver is returned directly.
 *
 * Observable outcomes: distinct MemoSpanTypefaceKey set and resolved Typeface identity per span.
 *
 * TDD proof:
 * - RED: `./kotlin test --include-module=ui-components --include-classes=com.lomo.ui.text.MemoTextPaintTypefaceTest`
 *   fails compilation until MemoSpanTypefaceKey, collectMemoSpanTypefaceKeys, and resolveMemoTypeface
 *   exist in MemoTextPaint.kt.
 * - GREEN: same command passes after wiring span-aware typeface lookup across MemoTextPaint,
 *   MemoTextDrawing, and MemoComposeParagraphText.
 *
 * Excludes: Android Canvas rasterization and FontFamilyResolver native asset decoding.
 */
class MemoTextPaintTypefaceTest : UiComponentsFunSpec() {
    init {
        test(
            "given base style and duplicate inline spans when collectMemoSpanTypefaceKeys runs then distinct style keys are collected",
        ) {
            val baseFamily = FontFamily.SansSerif
            val baseStyle =
                TextStyle(
                    fontFamily = baseFamily,
                    fontWeight = FontWeight.Normal,
                    fontStyle = FontStyle.Normal,
                )
            val text: AnnotatedString =
                buildAnnotatedString {
                    append("plain ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append("bold1 ")
                    }
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append("bold2 ")
                    }
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append("italic ")
                    }
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                        append("code")
                    }
                }

            val keys = collectMemoSpanTypefaceKeys(text, baseStyle)

            keys shouldContainExactlyInAnyOrder
                listOf(
                    MemoSpanTypefaceKey(baseFamily, FontWeight.Normal, FontStyle.Normal),
                    MemoSpanTypefaceKey(baseFamily, FontWeight.Bold, FontStyle.Normal),
                    MemoSpanTypefaceKey(baseFamily, FontWeight.Normal, FontStyle.Italic),
                    MemoSpanTypefaceKey(FontFamily.Monospace, FontWeight.Normal, FontStyle.Normal),
                )
        }

        test(
            "given a typefaceResolver when resolveMemoTypeface resolves base and inline spans then resolved typefaces are used directly",
        ) {
            val customFamily = FontFamily.Serif
            val baseStyle =
                TextStyle(
                    fontFamily = customFamily,
                    fontWeight = FontWeight.Medium,
                    fontStyle = FontStyle.Normal,
                )
            val regularTypeface = mockk<Typeface>()
            val boldTypeface = mockk<Typeface>()
            val italicTypeface = mockk<Typeface>()
            val monoTypeface = mockk<Typeface>()
            val lookup: MemoSpanTypefaceResolver = { family, weight, style ->
                when {
                    family == FontFamily.Monospace -> monoTypeface
                    weight == FontWeight.Bold && style == FontStyle.Normal -> boldTypeface
                    style == FontStyle.Italic -> italicTypeface
                    family == customFamily && weight == FontWeight.Medium -> regularTypeface
                    else -> null
                }
            }

            baseStyle.resolveMemoTypeface(
                SpanStyle(),
                customTypeface = null,
                typefaceResolver = lookup,
            ) shouldBe regularTypeface
            baseStyle.resolveMemoTypeface(
                SpanStyle(fontWeight = FontWeight.Bold),
                customTypeface = null,
                typefaceResolver = lookup,
            ) shouldBe boldTypeface
            baseStyle.resolveMemoTypeface(
                SpanStyle(fontStyle = FontStyle.Italic),
                customTypeface = null,
                typefaceResolver = lookup,
            ) shouldBe italicTypeface
            baseStyle.resolveMemoTypeface(
                SpanStyle(fontFamily = FontFamily.Monospace),
                customTypeface = null,
                typefaceResolver = lookup,
            ) shouldBe monoTypeface
        }
    }
}
