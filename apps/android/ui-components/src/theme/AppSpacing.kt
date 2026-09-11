package com.lomo.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Material Design 3 Spacing Token System
 * Based on 4dp grid system
 * Reference: https://m3.material.io/foundations/layout/understanding-layout/spacing
 *
 * Use these tokens for consistent spacing throughout the app.
 */
object AppSpacing {
    /** 4.dp - Minimal spacing for tightly grouped elements */
    val ExtraSmall = 4.dp

    /** 8.dp - Small spacing between related elements */
    val Small = 8.dp

    /** 12.dp - Medium-small spacing for list items */
    val MediumSmall = 12.dp

    /** 16.dp - Standard spacing for most content */
    val Medium = 16.dp

    /** 24.dp - Large spacing for section separation */
    val Large = 24.dp

    /** 32.dp - Extra large spacing for major sections */
    val ExtraLarge = 32.dp

    // --- Semantic Spacing ---

    /** Standard horizontal screen padding (16.dp) */
    val ScreenHorizontalPadding = 16.dp

    /** Standard vertical screen padding (16.dp) */
    val ScreenVerticalPadding = 16.dp

    /** Padding inside cards (16.dp) */
    val CardPadding = 16.dp

    /** Vertical spacing between list items (12.dp) */
    val ListItemSpacing = 12.dp

    /** Spacing between section header and content (8.dp) */
    val SectionHeaderSpacing = 8.dp

    /** Bottom padding to account for FAB (88.dp = FAB height + spacing) */
    val FabBottomPadding = 88.dp
}
