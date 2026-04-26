package com.lomo.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material Design 3 (Expressive) Shape Token System
 * Reference: https://m3.material.io/styles/shape/overview
 *
 * These tokens define the corner radius for every UI element so shape stays
 * consistent across the app. The Expressive ramp adds 20 / 32 / 48 dp tokens
 * plus common asymmetric shapes for bottom-sheet / drawer edges.
 */
object AppShapes {
    private const val FULL_SHAPE_PERCENT = 50

    /** 4.dp - Checkboxes, small icons, indicators */
    val ExtraSmall = RoundedCornerShape(4.dp)

    /** 8.dp - Chips, small buttons, tags */
    val Small = RoundedCornerShape(8.dp)

    /** 12.dp - Cards, dialogs, menus */
    val Medium = RoundedCornerShape(12.dp)

    /** 16.dp - Large containers, settings groups */
    val Large = RoundedCornerShape(16.dp)

    /** 20.dp - Expressive `large-increased`; elevated cards, prominent tiles */
    val LargeIncreased = RoundedCornerShape(20.dp)

    /** 28.dp - Legacy Material 3 extra-large; kept for call sites that intentionally pin to 28dp */
    val ExtraLarge = RoundedCornerShape(28.dp)

    /** 32.dp - Expressive `extra-large-increased`; FAB / SearchBar / Dialog / full-width containers */
    val ExtraLargeIncreased = RoundedCornerShape(32.dp)

    /** 48.dp - Expressive maximum corner; large hero surfaces and Expressive FAB-menu shells */
    val ExtraExtraLarge = RoundedCornerShape(48.dp)

    /** Full circular / pill shape */
    val Full = RoundedCornerShape(FULL_SHAPE_PERCENT)

    /** 8.dp top corners only; bottom-sheet first list item */
    val SmallTop =
        RoundedCornerShape(
            topStart = 8.dp,
            topEnd = 8.dp,
            bottomEnd = 0.dp,
            bottomStart = 0.dp,
        )

    /** 16.dp top corners only; bottom-sheet header / expandable card header */
    val MediumTop =
        RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomEnd = 0.dp,
            bottomStart = 0.dp,
        )

    /** 28.dp on the end edge; modal drawer sheet edge */
    val LargeEnd =
        RoundedCornerShape(
            topStart = 0.dp,
            topEnd = 28.dp,
            bottomEnd = 28.dp,
            bottomStart = 0.dp,
        )
}

/**
 * M3 Shapes slot configuration for MaterialTheme.
 *
 * The `extraLarge` slot is bound to the Expressive 32dp token so that every
 * M3 component that reads `MaterialTheme.shapes.extraLarge` (FAB, Dialog,
 * SearchBar, elevated Card) picks up the Expressive ramp without needing
 * per-call-site overrides.
 */
val Shapes =
    Shapes(
        extraSmall = AppShapes.ExtraSmall,
        small = AppShapes.Small,
        medium = AppShapes.Medium,
        large = AppShapes.Large,
        extraLarge = AppShapes.ExtraLargeIncreased,
    )
