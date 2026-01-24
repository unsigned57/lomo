package com.lomo.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material Design 3 Shape Token System
 * Reference: https://m3.material.io/styles/shape/overview
 *
 * These tokens define the corner radius for various UI elements
 * to ensure visual consistency across the app.
 */
object AppShapes {
    /** 4.dp - Used for checkboxes, small icons, indicators */
    val ExtraSmall = RoundedCornerShape(4.dp)

    /** 8.dp - Used for chips, small buttons, tags */
    val Small = RoundedCornerShape(8.dp)

    /** 12.dp - Used for cards, dialogs, menus */
    val Medium = RoundedCornerShape(12.dp)

    /** 16.dp - Used for large containers, settings groups */
    val Large = RoundedCornerShape(16.dp)

    /** 28.dp - Used for FABs, search bars, full-width containers */
    val ExtraLarge = RoundedCornerShape(28.dp)

    /** Full circular/pill shape */
    val Full = RoundedCornerShape(50)
}

/**
 * M3 Shapes configuration for MaterialTheme
 */
val Shapes =
    Shapes(
        extraSmall = AppShapes.ExtraSmall,
        small = AppShapes.Small,
        medium = AppShapes.Medium,
        large = AppShapes.Large,
        extraLarge = AppShapes.ExtraLarge,
    )
