package com.lomo.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

data class TypographyScales(
    val fontSizeScale: Float = 1.0f,
    val lineHeightScale: Float = 1.0f,
    val letterSpacingScale: Float = 1.0f,
    val paragraphSpacingScale: Float = 1.0f,
)

internal val LocalTypographyScales = staticCompositionLocalOf { TypographyScales() }

@Composable
internal fun currentTypographyScales(): TypographyScales = LocalTypographyScales.current

@Composable
fun ProvideTypographyScales(
    scales: TypographyScales,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalTypographyScales provides scales) {
        content()
    }
}
