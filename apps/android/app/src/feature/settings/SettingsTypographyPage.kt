package com.lomo.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.util.LocalAppHapticFeedback
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLocale
import com.lomo.app.R
import com.lomo.ui.theme.TypographyScales
import com.lomo.ui.theme.memoBodyTextStyle
import com.lomo.ui.theme.memoParagraphBlockSpacing
import kotlin.math.roundToInt

private const val SLIDER_RANGE_MIN = 0.5f
private const val SLIDER_RANGE_MAX = 3.0f
private const val STEP_INCREMENT = 0.05f
private const val PERCENT_SCALE = 100

internal fun formatTypographyScalePercent(scale: Float): String =
    "${(scale * PERCENT_SCALE).roundToInt()}%"

internal fun clampTypographyScale(value: Float): Float =
    value.coerceIn(SLIDER_RANGE_MIN, SLIDER_RANGE_MAX)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TypographySettingsPage(
    uiState: DisplaySectionState,
    displayFeature: SettingsDisplayFeatureViewModel,
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val haptic = LocalAppHapticFeedback.current

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
            LargeTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.TextFields,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.Medium))
                        Text(stringResource(R.string.settings_typography))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        haptic.medium()
                        onBack()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypographySliderItem(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = label, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = formatTypographyScalePercent(value),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
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
            valueRange = SLIDER_RANGE_MIN..SLIDER_RANGE_MAX,
        )
    LaunchedEffect(value) {
        if (sliderState.value != value) sliderState.value = value
    }
    SideEffect {
        sliderState.onValueChange = onValueChange
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(
            onClick = { onValueChange(clampTypographyScale(value - STEP_INCREMENT)) },
            enabled = value > SLIDER_RANGE_MIN,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Remove,
                contentDescription = decrementContentDescription,
            )
        }
        Slider(
            state = sliderState,
            modifier =
                Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    drawStopIndicator = null,
                )
            },
        )
        FilledTonalIconButton(
            onClick = { onValueChange(clampTypographyScale(value + STEP_INCREMENT)) },
            enabled = value < SLIDER_RANGE_MAX,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = incrementContentDescription,
            )
        }
    }
}
