package com.lomo.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class TypographyScales(
    val fontSizeScale: Float = 1.0f,
    val lineHeightScale: Float = 1.0f,
    val letterSpacingScale: Float = 1.0f,
    val paragraphSpacingScale: Float = 1.0f,
)

private object TypographyScaleStateHolder {
    var current by mutableStateOf(TypographyScales())
}

@Composable
internal fun currentTypographyScales(): TypographyScales = TypographyScaleStateHolder.current

fun updateTypographyScales(scales: TypographyScales) {
    TypographyScaleStateHolder.current = scales
}
