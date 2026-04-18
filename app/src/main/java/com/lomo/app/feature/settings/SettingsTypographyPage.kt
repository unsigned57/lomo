package com.lomo.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLocale
import com.lomo.app.R
import com.lomo.ui.theme.TypographyScales
import com.lomo.ui.theme.memoBodyTextStyle
import com.lomo.ui.theme.memoParagraphBlockSpacing

private const val SLIDER_RANGE_MIN = 0.5f
private const val SLIDER_RANGE_MAX = 2.0f
private const val SLIDER_STEPS = 14

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TypographySettingsPage(
    uiState: DisplaySectionState,
    displayFeature: SettingsDisplayFeatureViewModel,
    onBack: () -> Unit,
) {
    val scales = TypographyScales(
        fontSizeScale = uiState.typographyFontSizeScale,
        lineHeightScale = uiState.typographyLineHeightScale,
        letterSpacingScale = uiState.typographyLetterSpacingScale,
        paragraphSpacingScale = uiState.typographyParagraphSpacingScale,
    )
    val textStyle = MaterialTheme.typography.memoBodyTextStyle(scales)
    val paragraphSpacing = memoParagraphBlockSpacing(scales)
    val isChinese = LocalLocale.current.platformLocale.language == "zh"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_typography)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
        ) {
            TypographyPreviewCard(
                textStyle = textStyle,
                paragraphSpacing = paragraphSpacing,
                isChinese = isChinese,
            )

            Spacer(modifier = Modifier.height(24.dp))

            TypographySliderItem(
                label = stringResource(R.string.settings_typography_font_size),
                value = uiState.typographyFontSizeScale,
                onValueChange = displayFeature::updateTypographyFontSizeScale,
            )
            Spacer(modifier = Modifier.height(16.dp))
            TypographySliderItem(
                label = stringResource(R.string.settings_typography_line_height),
                value = uiState.typographyLineHeightScale,
                onValueChange = displayFeature::updateTypographyLineHeightScale,
            )
            Spacer(modifier = Modifier.height(16.dp))
            TypographySliderItem(
                label = stringResource(R.string.settings_typography_letter_spacing),
                value = uiState.typographyLetterSpacingScale,
                onValueChange = displayFeature::updateTypographyLetterSpacingScale,
            )
            Spacer(modifier = Modifier.height(16.dp))
            TypographySliderItem(
                label = stringResource(R.string.settings_typography_paragraph_spacing),
                value = uiState.typographyParagraphSpacingScale,
                onValueChange = displayFeature::updateTypographyParagraphSpacingScale,
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun TypographyPreviewCard(
    textStyle: androidx.compose.ui.text.TextStyle,
    paragraphSpacing: androidx.compose.ui.unit.Dp,
    isChinese: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            val paragraph1 =
                if (isChinese) {
                    stringResource(R.string.settings_typography_preview_zh_1)
                } else {
                    stringResource(R.string.settings_typography_preview_en_1)
                }
            val paragraph2 =
                if (isChinese) {
                    stringResource(R.string.settings_typography_preview_zh_2)
                } else {
                    stringResource(R.string.settings_typography_preview_en_2)
                }
            Text(text = paragraph1, style = textStyle)
            Spacer(modifier = Modifier.height(paragraphSpacing))
            Text(text = paragraph2, style = textStyle)
        }
    }
}

@Composable
private fun TypographySliderItem(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(text = "${(value * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = SLIDER_RANGE_MIN..SLIDER_RANGE_MAX,
            steps = SLIDER_STEPS,
        )
    }
}
