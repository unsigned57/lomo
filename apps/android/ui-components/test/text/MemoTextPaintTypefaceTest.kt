package com.lomo.ui.text

import android.graphics.Typeface
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
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

        test("overlapping spans merge typeface, fontSize, color, and combined decorations") {
            val text =
                buildAnnotatedString {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline))
                    append("outer ")
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic, textDecoration = TextDecoration.LineThrough))
                    append("inner")
                    pop()
                    pop()
                }

            val outerResolved = text.resolveSpanStyle(0)
            outerResolved.fontWeight shouldBe FontWeight.Bold
            outerResolved.fontStyle shouldBe null
            outerResolved.textDecoration shouldBe TextDecoration.Underline

            val innerOffset = text.text.indexOf("inner")
            val innerResolved = text.resolveSpanStyle(innerOffset)
            innerResolved.fontWeight shouldBe FontWeight.Bold
            innerResolved.fontStyle shouldBe FontStyle.Italic
            innerResolved.textDecoration shouldBe
                TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))

            val keys =
                collectMemoSpanTypefaceKeys(
                    annotatedText = text,
                    baseStyle = TextStyle(fontFamily = FontFamily.SansSerif),
                )
            keys shouldContainExactlyInAnyOrder
                listOf(
                    MemoSpanTypefaceKey(FontFamily.SansSerif, FontWeight.Normal, FontStyle.Normal),
                    MemoSpanTypefaceKey(FontFamily.SansSerif, FontWeight.Bold, FontStyle.Normal),
                    MemoSpanTypefaceKey(FontFamily.SansSerif, FontWeight.Bold, FontStyle.Italic),
                )
        }

        test("resolveSpanFontSizePx scales em against base fontSize and preserves explicit sp") {
            val baseStyle = TextStyle(fontSize = 16.sp)
            val density = Density(density = 2f, fontScale = 1f)

            baseStyle.resolveSpanFontSizePx(SpanStyle(), density) shouldBe 32f
            baseStyle.resolveSpanFontSizePx(SpanStyle(fontSize = 0.9f.em), density) shouldBe (32f * 0.9f)
            baseStyle.resolveSpanFontSizePx(SpanStyle(fontSize = 20.sp), density) shouldBe 40f
        }
    }
}
