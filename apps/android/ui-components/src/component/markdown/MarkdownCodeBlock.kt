package com.lomo.ui.component.markdown

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.lomo.ui.theme.currentTypographyScales

@Composable
internal fun MarkdownCodeBlock(language: String?, literal: String, modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val typographyScales = currentTypographyScales()
    val bodyMedium = MaterialTheme.typography.bodyMedium
    val scaledFontSize =
        scaleTextUnit(bodyMedium.fontSize, typographyScales.fontSizeScale)
    val scaledLineHeight =
        scaleTextUnit(bodyMedium.lineHeight, typographyScales.lineHeightScale)
    val codeStyle =
        bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = scaledFontSize,
            lineHeight = scaledLineHeight,
        )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (!language.isNullOrBlank()) {
                Row(
                    modifier =
                    Modifier.fillMaxWidth()
                        .background(colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = language,
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                HorizontalDivider(thickness = 1.dp, color = colorScheme.outlineVariant)
            }
            Box(
                modifier =
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    text = literal,
                    style = codeStyle,
                    color = colorScheme.onSurface,
                    softWrap = false,
                )
            }
        }
    }
}

private fun scaleTextUnit(unit: TextUnit, scale: Float): TextUnit = if (unit.isSpecified) unit * scale else unit
