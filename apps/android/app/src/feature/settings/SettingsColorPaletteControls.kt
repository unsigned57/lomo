package com.lomo.app.feature.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lomo.app.R
import com.lomo.domain.model.ColorPresetId
import com.lomo.domain.model.ContrastLevel
import com.lomo.ui.icon.LomoIcon
import com.lomo.ui.icon.LomoIcons
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.LocalLomoMotion
import com.lomo.ui.theme.colorSchemeFromSeed
import com.lomo.ui.theme.withPureBlackSurfaces
import com.lomo.ui.util.LocalAppHapticFeedback

private const val PRESET_GRID_COLUMNS = 4
private const val TOP_SEMICIRCLE_START_ANGLE = 180f
private const val TOP_SEMICIRCLE_SWEEP_ANGLE = 180f
private const val BOTTOM_RIGHT_START_ANGLE = 0f
private const val BOTTOM_LEFT_START_ANGLE = 90f
private const val QUARTER_CIRCLE_SWEEP_ANGLE = 90f
private const val SELECTED_PRESET_CONTAINER_ALPHA = 0.42f
private val PRESET_SWATCH_SIZE = 48.dp
private val DYNAMIC_SWATCH_SIZE = 44.dp
private val SWATCH_SELECTED_BADGE_SIZE = 20.dp
private val SWATCH_SELECTED_ICON_SIZE = 13.dp
private val COLOR_SLIDER_TRACK_HEIGHT = 14.dp
private val COLOR_SLIDER_THUMB_SIZE = 24.dp
private val COLOR_SLIDER_THUMB_INNER_PADDING = 3.dp
private const val COLOR_PREVIEW_WORD_COUNT = 284
private val COLOR_ROLE_SWATCH_SIZE = 12.dp

@Composable
internal fun MaterialYouThreeToneSwatch(
    primary: Color,
    primaryContainer: Color,
    tertiary: Color,
    selected: Boolean,
    checkContainerColor: Color,
    checkTint: Color,
    modifier: Modifier = Modifier,
    size: Dp = PRESET_SWATCH_SIZE,
) {
    val ringColor by animateColorAsState(
        targetValue = if (selected) primary else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = LocalLomoMotion.current.component.fastEffectsSpec(),
        label = "SwatchRing",
    )
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .border(
                width = if (selected) 2.5.dp else 1.dp,
                color = ringColor,
                shape = CircleShape,
            )
            .padding(if (selected) 3.dp else 0.dp)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            drawArc(
                color = primaryContainer,
                startAngle = TOP_SEMICIRCLE_START_ANGLE,
                sweepAngle = TOP_SEMICIRCLE_SWEEP_ANGLE,
                useCenter = true,
            )
            drawArc(
                color = tertiary,
                startAngle = BOTTOM_RIGHT_START_ANGLE,
                sweepAngle = QUARTER_CIRCLE_SWEEP_ANGLE,
                useCenter = true,
            )
            drawArc(
                color = primary,
                startAngle = BOTTOM_LEFT_START_ANGLE,
                sweepAngle = QUARTER_CIRCLE_SWEEP_ANGLE,
                useCenter = true,
            )
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .size(SWATCH_SELECTED_BADGE_SIZE)
                    .clip(CircleShape)
                    .background(checkContainerColor),
                contentAlignment = Alignment.Center,
            ) {
                LomoIcon(
                    icon = LomoIcons.Check,
                    contentDescription = null,
                    tint = checkTint,
                    modifier = Modifier.size(SWATCH_SELECTED_ICON_SIZE),
                )
            }
        }
    }
}

@Composable
internal fun CuratedPresetsCard(
    selectedPreset: ColorPresetId?,
    isDarkPreview: Boolean,
    contrastLevel: ContrastLevel,
    pureBlackDark: Boolean,
    onSelectPreset: (ColorPresetId) -> Unit,
) {
    val rows = remember { ColorPresetId.entries.chunked(PRESET_GRID_COLUMNS) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.LargeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.MediumSmall),
        ) {
            Text(
                text = stringResource(R.string.settings_color_presets_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
            ) {
                rows.forEach { rowPresets ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
                    ) {
                        rowPresets.forEach { preset ->
                            CuratedPresetTile(
                                preset = preset,
                                selected = selectedPreset == preset,
                                isDarkPreview = isDarkPreview,
                                contrastLevel = contrastLevel,
                                pureBlackDark = pureBlackDark,
                                onSelect = { onSelectPreset(preset) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(PRESET_GRID_COLUMNS - rowPresets.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CuratedPresetTile(
    preset: ColorPresetId,
    selected: Boolean,
    isDarkPreview: Boolean,
    contrastLevel: ContrastLevel,
    pureBlackDark: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalAppHapticFeedback.current
    val presetScheme = remember(preset, isDarkPreview, contrastLevel, pureBlackDark) {
        colorSchemeFromSeed(preset.seedArgb, isDarkPreview, contrastLevel)
            .withPureBlackSurfaces(isDark = isDarkPreview, enabled = pureBlackDark)
    }
    val tileColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = SELECTED_PRESET_CONTAINER_ALPHA)
        } else {
            Color.Transparent
        },
        animationSpec = LocalLomoMotion.current.component.fastEffectsSpec(),
        label = "PresetTileBg",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(AppShapes.Large)
            .background(tileColor)
            .clickable(role = Role.RadioButton) {
                haptic.medium()
                onSelect()
            }
            .padding(vertical = 8.dp, horizontal = 4.dp),
    ) {
        MaterialYouThreeToneSwatch(
            primary = presetScheme.primary,
            primaryContainer = presetScheme.primaryContainer,
            tertiary = presetScheme.tertiary,
            selected = selected,
            checkContainerColor = presetScheme.primary,
            checkTint = presetScheme.onPrimary,
            size = PRESET_SWATCH_SIZE,
        )
        Text(
            text = presetDisplayName(preset),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ColorSchemePreviewCard(colorScheme: ColorScheme, previewIsDark: Boolean, onTogglePreviewDark: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.LargeIncreased,
        color = colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_color_palette_preview_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                PreviewModeToggleChip(
                    previewIsDark = previewIsDark,
                    onToggle = onTogglePreviewDark,
                )
            }
            MockNotePreviewCard(colorScheme = colorScheme)
            ColorSchemeRoleStrip(colorScheme = colorScheme)
        }
    }
}

@Composable
private fun MockNotePreviewCard(colorScheme: ColorScheme) {
    Surface(
        shape = AppShapes.Large,
        color = colorScheme.surfaceContainer,
        border = BorderStroke(width = 1.dp, color = colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            MockNotePreviewHeader(colorScheme = colorScheme)
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.settings_color_preview_note_title),
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_color_preview_note_body),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
                lineHeight = 22.sp,
            )
            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = pluralStringResource(
                        R.plurals.settings_color_preview_word_count,
                        COLOR_PREVIEW_WORD_COUNT,
                        COLOR_PREVIEW_WORD_COUNT,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = AppShapes.Full,
                    color = colorScheme.primary,
                ) {
                    Text(
                        text = stringResource(R.string.settings_color_preview_edited_time),
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MockNotePreviewHeader(colorScheme: ColorScheme) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.settings_color_preview_avatar_initial),
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_color_preview_source),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.settings_color_preview_time),
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            shape = CircleShape,
            color = colorScheme.secondaryContainer,
            modifier = Modifier.height(24.dp),
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.settings_color_preview_tag),
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun PreviewModeToggleChip(previewIsDark: Boolean, onToggle: () -> Unit) {
    val haptic = LocalAppHapticFeedback.current
    Surface(
        shape = AppShapes.Full,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(AppShapes.Full)
            .clickable(role = Role.Button) {
                haptic.medium()
                onToggle()
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            LomoIcon(
                icon = if (previewIsDark) LomoIcons.DarkMode else LomoIcons.WbSunny,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = stringResource(
                    if (previewIsDark) {
                        R.string.settings_color_preview_mode_dark
                    } else {
                        R.string.settings_color_preview_mode_light
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun ColorSchemeRoleStrip(colorScheme: ColorScheme) {
    val roles = listOf(
        Triple(stringResource(R.string.settings_color_role_primary), colorScheme.primary, colorScheme.onSurface),
        Triple(stringResource(R.string.settings_color_role_secondary), colorScheme.secondary, colorScheme.onSurface),
        Triple(stringResource(R.string.settings_color_role_tertiary), colorScheme.tertiary, colorScheme.onSurface),
        Triple(
            stringResource(R.string.settings_color_role_container),
            colorScheme.primaryContainer,
            colorScheme.onSurface,
        ),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        roles.forEach { (label, swatchColor, textColor) ->
            Surface(
                modifier = Modifier.weight(1f),
                shape = AppShapes.Medium,
                color = colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(COLOR_ROLE_SWATCH_SIZE)
                            .clip(CircleShape)
                            .background(swatchColor)
                            .border(0.5.dp, colorScheme.outlineVariant, CircleShape),
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColorGradientSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    trackColors: List<Color>,
    thumbColor: Color,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    val sliderState = rememberSliderState(value = value, trackRange = valueRange)
    LaunchedEffect(value) {
        if (sliderState.value != value) sliderState.value = value
    }
    val surfaceColor = MaterialTheme.colorScheme.surface
    val outlineColor = MaterialTheme.colorScheme.outlineVariant

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Surface(
                shape = AppShapes.Full,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Slider(
            state = sliderState,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            modifier = Modifier.fillMaxWidth(),
            thumb = {
                Box(
                    modifier = Modifier
                        .size(COLOR_SLIDER_THUMB_SIZE)
                        .clip(CircleShape)
                        .background(surfaceColor)
                        .border(1.dp, outlineColor, CircleShape)
                        .padding(COLOR_SLIDER_THUMB_INNER_PADDING)
                        .clip(CircleShape)
                        .background(thumbColor),
                )
            },
            track = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(COLOR_SLIDER_TRACK_HEIGHT)
                        .clip(AppShapes.Full)
                        .background(Brush.horizontalGradient(trackColors))
                        .border(1.dp, outlineColor, AppShapes.Full),
                )
            },
        )
    }
}

internal val ColorDynamicSwatchSize: Dp = DYNAMIC_SWATCH_SIZE
