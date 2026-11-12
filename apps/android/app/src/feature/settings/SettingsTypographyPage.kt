package com.lomo.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.ui.component.common.LomoFilledTonalIconButton
import com.lomo.ui.component.common.lomoCompactInteractiveHeight
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.TypographyScales
import com.lomo.ui.theme.memoBodyTextStyle
import com.lomo.ui.theme.memoParagraphBlockSpacing
import kotlin.math.roundToInt

private const val SLIDER_RANGE_MIN = 0.5f
private const val SLIDER_RANGE_MAX = 3.0f
private const val STEP_INCREMENT = 0.05f
private const val PERCENT_SCALE = 100

internal fun formatTypographyScalePercent(scale: Float): String = "${(scale * PERCENT_SCALE).roundToInt()}%"

internal fun clampTypographyScale(value: Float): Float = value.coerceIn(SLIDER_RANGE_MIN, SLIDER_RANGE_MAX)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TypographySettingsPage(
    uiState: DisplaySectionState,
    displayFeature: SettingsDisplayFeatureViewModel,
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

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
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SettingsPageTopAppBar(
                title = stringResource(R.string.settings_typography),
                scrollBehavior = scrollBehavior,
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = AppSpacing.ScreenHorizontalPadding,
                    vertical = AppSpacing.MediumSmall,
                ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
        ) {
            TypographyPreviewCard(
                textStyle = textStyle,
                paragraphSpacing = paragraphSpacing,
                isChinese = isChinese,
            )
            TypographySliderItem(
                label = stringResource(R.string.settings_typography_font_size),
                value = uiState.typographyFontSizeScale,
                onValueChange = displayFeature::updateTypographyFontSizeScale,
            )
            TypographySliderItem(
                label = stringResource(R.string.settings_typography_line_height),
                value = uiState.typographyLineHeightScale,
                onValueChange = displayFeature::updateTypographyLineHeightScale,
            )
            TypographySliderItem(
                label = stringResource(R.string.settings_typography_letter_spacing),
                value = uiState.typographyLetterSpacingScale,
                onValueChange = displayFeature::updateTypographyLetterSpacingScale,
            )
            TypographySliderItem(
                label = stringResource(R.string.settings_typography_paragraph_spacing),
                value = uiState.typographyParagraphSpacingScale,
                onValueChange = displayFeature::updateTypographyParagraphSpacingScale,
            )
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
        shape = AppShapes.LargeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.settings_font_preview_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(12.dp))
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
            Text(
                text = paragraph1,
                style = textStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(paragraphSpacing))
            Text(
                text = paragraph2,
                style = textStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypographySliderItem(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Card(
        shape = AppShapes.LargeIncreased,
        colors =
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Surface(
                    shape = AppShapes.Full,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = formatTypographyScalePercent(value),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            TypographyScaleAdjustmentRow(
                value = value,
                onValueChange = onValueChange,
                decrementContentDescription = stringResource(R.string.cd_typography_step_decrement),
                incrementContentDescription = stringResource(R.string.cd_typography_step_increment),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypographyScaleAdjustmentRow(
    value: Float,
    onValueChange: (Float) -> Unit,
    decrementContentDescription: String,
    incrementContentDescription: String,
) {
    val sliderState =
        rememberSliderState(
            value = value,
            trackRange = SLIDER_RANGE_MIN..SLIDER_RANGE_MAX,
        )
    LaunchedEffect(value) {
        if (sliderState.value != value) sliderState.value = value
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        LomoFilledTonalIconButton(
            onClick = { onValueChange(clampTypographyScale(value - STEP_INCREMENT)) },
            enabled = value > SLIDER_RANGE_MIN,
            modifier =
            Modifier
                .lomoCompactInteractiveHeight(36.dp)
                .size(36.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Remove,
                contentDescription = decrementContentDescription,
                modifier = Modifier.size(18.dp),
            )
        }
        Slider(
            state = sliderState,
            onValueChange = onValueChange,
            modifier =
            Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    drawStopIndicator = null,
                )
            },
        )
        LomoFilledTonalIconButton(
            onClick = { onValueChange(clampTypographyScale(value + STEP_INCREMENT)) },
            enabled = value < SLIDER_RANGE_MAX,
            modifier =
            Modifier
                .lomoCompactInteractiveHeight(36.dp)
                .size(36.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = incrementContentDescription,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
