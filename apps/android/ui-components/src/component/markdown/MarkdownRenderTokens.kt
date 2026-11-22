package com.lomo.ui.component.markdown

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

internal data class MarkdownHeadingScale(val fontScale: Float, val lineScale: Float, val weight: FontWeight)

/** Semantic color and typography scale tokens for IR-driven Markdown rendering. */
internal object MarkdownRenderTokens {
    private const val WIKI_PILL_ALPHA = 0.35f
    private const val QUOTE_BAR_ALPHA = 0.7f
    private const val QUOTE_CONTAINER_ALPHA = 0.55f
    private const val HTML_CONTAINER_ALPHA = 0.45f
    private const val TABLE_ZEBRA_ROW_ALPHA = 0.45f

    private const val HEADING_LEVEL_1 = 1
    private const val HEADING_LEVEL_2 = 2
    private const val HEADING_LEVEL_3 = 3
    private const val HEADING_LEVEL_4 = 4
    private const val HEADING_LEVEL_5 = 5
    private const val HEADING_LEVEL_6 = 6

    private const val H1_FONT_SCALE = 1.44f
    private const val H1_LINE_SCALE = 1.32f
    private const val H2_FONT_SCALE = 1.26f
    private const val H2_LINE_SCALE = 1.22f
    private const val H3_FONT_SCALE = 1.14f
    private const val H3_LINE_SCALE = 1.14f
    private const val H4_FONT_SCALE = 1.06f
    private const val H4_LINE_SCALE = 1.08f
    private const val H5_FONT_SCALE = 1.00f
    private const val H5_LINE_SCALE = 1.02f
    private const val H6_FONT_SCALE = 0.94f
    private const val H6_LINE_SCALE = 1.00f

    fun wikiPillBackgroundColor(colorScheme: ColorScheme): Color =
        colorScheme.primaryContainer.copy(alpha = WIKI_PILL_ALPHA)

    fun quoteAccentBarColor(colorScheme: ColorScheme): Color = colorScheme.primary.copy(alpha = QUOTE_BAR_ALPHA)

    fun quoteContainerColor(colorScheme: ColorScheme): Color =
        colorScheme.surfaceContainerHigh.copy(alpha = QUOTE_CONTAINER_ALPHA)

    fun htmlBlockContainerColor(colorScheme: ColorScheme): Color =
        colorScheme.surfaceContainerHigh.copy(alpha = HTML_CONTAINER_ALPHA)

    fun tableZebraRowColor(colorScheme: ColorScheme): Color =
        colorScheme.surfaceContainer.copy(alpha = TABLE_ZEBRA_ROW_ALPHA)

    fun headingScale(level: UInt): MarkdownHeadingScale = when (level.toInt()) {
        HEADING_LEVEL_1 -> MarkdownHeadingScale(H1_FONT_SCALE, H1_LINE_SCALE, FontWeight.Bold)
        HEADING_LEVEL_2 -> MarkdownHeadingScale(H2_FONT_SCALE, H2_LINE_SCALE, FontWeight.Bold)
        HEADING_LEVEL_3 -> MarkdownHeadingScale(H3_FONT_SCALE, H3_LINE_SCALE, FontWeight.SemiBold)
        HEADING_LEVEL_4 -> MarkdownHeadingScale(H4_FONT_SCALE, H4_LINE_SCALE, FontWeight.SemiBold)
        HEADING_LEVEL_5 -> MarkdownHeadingScale(H5_FONT_SCALE, H5_LINE_SCALE, FontWeight.SemiBold)
        else -> MarkdownHeadingScale(H6_FONT_SCALE, H6_LINE_SCALE, FontWeight.Medium)
    }

    fun isMutedHeadingLevel(level: UInt): Boolean = level.toInt() >= HEADING_LEVEL_6
}
