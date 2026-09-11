package com.lomo.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Semantic colours that live outside the seed-derived M3 [androidx.compose.material3.ColorScheme].
 *
 * The previously hardcoded brand palette (Indigo/Slate/Cyan plus Neutral/Surface/Outline tokens)
 * is no longer kept here — every `ColorScheme` slot is now derived from a [com.lomo.domain.model.ColorSource]
 * via [colorSchemeFromSeed] (see Theme.kt). Re-introducing fixed brand constants here would
 * resurrect a parallel colour pipeline that the new model is designed to make impossible.
 */

private const val SUCCESS_LIGHT_HEX = 0xFF1B6D2F
private const val SUCCESS_DARK_HEX = 0xFF7BDB8F
private const val SUCCESS_CONTAINER_LIGHT_HEX = 0xFFA6F5A9
private const val SUCCESS_CONTAINER_DARK_HEX = 0xFF005319
private const val ON_SUCCESS_CONTAINER_LIGHT_HEX = 0xFF002106
private const val ON_SUCCESS_CONTAINER_DARK_HEX = 0xFFA6F5A9

private const val WARNING_LIGHT_HEX = 0xFF7C5800
private const val WARNING_DARK_HEX = 0xFFF5C34B
private const val WARNING_CONTAINER_LIGHT_HEX = 0xFFFFE08C
private const val WARNING_CONTAINER_DARK_HEX = 0xFF5D4200
private const val ON_WARNING_CONTAINER_LIGHT_HEX = 0xFF261A00
private const val ON_WARNING_CONTAINER_DARK_HEX = 0xFFFFE08C

// Semantic Colors - Success (Green)
val SuccessLight = Color(SUCCESS_LIGHT_HEX)
val SuccessDark = Color(SUCCESS_DARK_HEX)
val SuccessContainerLight = Color(SUCCESS_CONTAINER_LIGHT_HEX)
val SuccessContainerDark = Color(SUCCESS_CONTAINER_DARK_HEX)
val OnSuccessContainerLight = Color(ON_SUCCESS_CONTAINER_LIGHT_HEX)
val OnSuccessContainerDark = Color(ON_SUCCESS_CONTAINER_DARK_HEX)

// Semantic Colors - Warning (Amber/Orange)
val WarningLight = Color(WARNING_LIGHT_HEX)
val WarningDark = Color(WARNING_DARK_HEX)
val WarningContainerLight = Color(WARNING_CONTAINER_LIGHT_HEX)
val WarningContainerDark = Color(WARNING_CONTAINER_DARK_HEX)
val OnWarningContainerLight = Color(ON_WARNING_CONTAINER_LIGHT_HEX)
val OnWarningContainerDark = Color(ON_WARNING_CONTAINER_DARK_HEX)
