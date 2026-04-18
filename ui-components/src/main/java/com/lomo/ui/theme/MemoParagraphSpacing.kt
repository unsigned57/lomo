package com.lomo.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun memoParagraphBlockSpacing(scales: TypographyScales = TypographyScales()): Dp =
    8.dp * scales.paragraphSpacingScale

@Composable
fun memoParagraphBlockSpacing(): Dp = memoParagraphBlockSpacing(currentTypographyScales())
