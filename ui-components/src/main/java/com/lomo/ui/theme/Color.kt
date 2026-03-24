package com.lomo.ui.theme

import androidx.compose.ui.graphics.Color

// Premium Slate & Indigo Palette

private const val INDIGO_80_HEX = 0xFFBAC3FF
private const val INDIGO_40_HEX = 0xFF3A4CB8
private const val INDIGO_PRIMARY_LIGHT_HEX = 0xFF4F63D6
private const val INDIGO_PRIMARY_DARK_HEX = 0xFFBAC3FF
private const val INDIGO_CONTAINER_DARK_HEX = 0xFF283275
private const val INDIGO_CONTAINER_LIGHT_HEX = 0xFFDEE0FF
private const val ON_INDIGO_CONTAINER_LIGHT_HEX = 0xFF00105C

private const val SLATE_80_HEX = 0xFFC5C9D6
private const val SLATE_40_HEX = 0xFF535D6E
private const val SLATE_SECONDARY_LIGHT_HEX = 0xFF5A667A
private const val SLATE_SECONDARY_DARK_HEX = 0xFFC0C7D5
private const val SLATE_CONTAINER_DARK_HEX = 0xFF3B4656
private const val SLATE_CONTAINER_LIGHT_HEX = 0xFFDCE2F0
private const val ON_SLATE_CONTAINER_LIGHT_HEX = 0xFF131C2B

private const val CYAN_80_HEX = 0xFFA6EEFF
private const val CYAN_40_HEX = 0xFF00687A
private const val CYAN_TERTIARY_LIGHT_HEX = 0xFF00869B
private const val CYAN_TERTIARY_DARK_HEX = 0xFF76D6EE

private const val NEUTRAL_99_HEX = 0xFFFCFCFF
private const val NEUTRAL_95_HEX = 0xFFEEF0FA
private const val NEUTRAL_90_HEX = 0xFFE0E2EC
private const val NEUTRAL_80_HEX = 0xFFC4C7D0
private const val NEUTRAL_10_HEX = 0xFF191C22
private const val NEUTRAL_12_HEX = 0xFF1F2229
private const val NEUTRAL_20_HEX = 0xFF2E313A
private const val NEUTRAL_30_HEX = 0xFF454751

private const val SURFACE_CONTAINER_LOWEST_LIGHT_HEX = 0xFFFFFFFF
private const val SURFACE_CONTAINER_LOW_LIGHT_HEX = 0xFFF6F7FB
private const val SURFACE_CONTAINER_LIGHT_HEX = 0xFFF0F2F8
private const val SURFACE_CONTAINER_HIGH_LIGHT_HEX = 0xFFEBEDF3
private const val SURFACE_CONTAINER_HIGHEST_LIGHT_HEX = 0xFFE5E7EE

private const val SURFACE_CONTAINER_LOWEST_DARK_HEX = 0xFF0E1015
private const val SURFACE_CONTAINER_LOW_DARK_HEX = 0xFF191C22
private const val SURFACE_CONTAINER_DARK_HEX = 0xFF1D2026
private const val SURFACE_CONTAINER_HIGH_DARK_HEX = 0xFF272A31
private const val SURFACE_CONTAINER_HIGHEST_DARK_HEX = 0xFF32353D

private const val ERROR_LIGHT_HEX = 0xFFBA1A1A
private const val ERROR_DARK_HEX = 0xFFFFB4AB

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

private const val OUTLINE_LIGHT_HEX = 0xFF74777F
private const val OUTLINE_DARK_HEX = 0xFF8E9099
private const val OUTLINE_VARIANT_LIGHT_HEX = 0xFFC4C6D0
private const val OUTLINE_VARIANT_DARK_HEX = 0xFF44474F

// Primary - Indigo
val Indigo80 = Color(INDIGO_80_HEX)
val Indigo40 = Color(INDIGO_40_HEX)
val IndigoPrimaryLight = Color(INDIGO_PRIMARY_LIGHT_HEX)
val IndigoPrimaryDark = Color(INDIGO_PRIMARY_DARK_HEX)
val IndigoContainerDark = Color(INDIGO_CONTAINER_DARK_HEX)
val IndigoContainerLight = Color(INDIGO_CONTAINER_LIGHT_HEX)
val OnIndigoContainerLight = Color(ON_INDIGO_CONTAINER_LIGHT_HEX)

// Secondary - Slate
val Slate80 = Color(SLATE_80_HEX)
val Slate40 = Color(SLATE_40_HEX)
val SlateSecondaryLight = Color(SLATE_SECONDARY_LIGHT_HEX)
val SlateSecondaryDark = Color(SLATE_SECONDARY_DARK_HEX)
val SlateContainerDark = Color(SLATE_CONTAINER_DARK_HEX)
val SlateContainerLight = Color(SLATE_CONTAINER_LIGHT_HEX)
val OnSlateContainerLight = Color(ON_SLATE_CONTAINER_LIGHT_HEX)

// Tertiary - Teal/Cyan (Accents)
val Cyan80 = Color(CYAN_80_HEX)
val Cyan40 = Color(CYAN_40_HEX)
val CyanTertiaryLight = Color(CYAN_TERTIARY_LIGHT_HEX)
val CyanTertiaryDark = Color(CYAN_TERTIARY_DARK_HEX)

// Neutral / Surface
val Neutral99 = Color(NEUTRAL_99_HEX) // Nearly White
val Neutral95 = Color(NEUTRAL_95_HEX)
val Neutral90 = Color(NEUTRAL_90_HEX)
val Neutral80 = Color(NEUTRAL_80_HEX)

val Neutral10 = Color(NEUTRAL_10_HEX) // Deep Charcoal
val Neutral12 = Color(NEUTRAL_12_HEX) // Slightly Lighter
val Neutral20 = Color(NEUTRAL_20_HEX)
val Neutral30 = Color(NEUTRAL_30_HEX)

// Surface Container Colors (M3)
// Light
val SurfaceContainerLowestLight = Color(SURFACE_CONTAINER_LOWEST_LIGHT_HEX)
val SurfaceContainerLowLight = Color(SURFACE_CONTAINER_LOW_LIGHT_HEX)
val SurfaceContainerLight = Color(SURFACE_CONTAINER_LIGHT_HEX)
val SurfaceContainerHighLight = Color(SURFACE_CONTAINER_HIGH_LIGHT_HEX)
val SurfaceContainerHighestLight = Color(SURFACE_CONTAINER_HIGHEST_LIGHT_HEX)

// Dark
val SurfaceContainerLowestDark = Color(SURFACE_CONTAINER_LOWEST_DARK_HEX)
val SurfaceContainerLowDark = Color(SURFACE_CONTAINER_LOW_DARK_HEX) // Neutral10
val SurfaceContainerDark = Color(SURFACE_CONTAINER_DARK_HEX)
val SurfaceContainerHighDark = Color(SURFACE_CONTAINER_HIGH_DARK_HEX)
val SurfaceContainerHighestDark = Color(SURFACE_CONTAINER_HIGHEST_DARK_HEX)

// Semantic Colors - Error
val ErrorLight = Color(ERROR_LIGHT_HEX)
val ErrorDark = Color(ERROR_DARK_HEX)

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

// Outline Colors (M3)
val OutlineLight = Color(OUTLINE_LIGHT_HEX)
val OutlineDark = Color(OUTLINE_DARK_HEX)
val OutlineVariantLight = Color(OUTLINE_VARIANT_LIGHT_HEX)
val OutlineVariantDark = Color(OUTLINE_VARIANT_DARK_HEX)
