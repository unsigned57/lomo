package com.lomo.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import com.lomo.app.R
import com.lomo.domain.model.ColorSource
import com.lomo.ui.theme.AppSpacing
import com.lomo.domain.model.ThemeMode
import com.lomo.ui.theme.colorSchemeFromSeed
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.collections.immutable.ImmutableList

private const val HUE_MAX = 360f
private const val SATURATION_MAX = 1f
private const val LIGHTNESS_MAX = 1f
private const val SEED_PREVIEW_SWATCH_DP = 56
private const val DEFAULT_CUSTOM_SEED_ARGB: Int = 0xFF4F63D6.toInt()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColorPaletteSettingsPage(
    uiState: DisplaySectionState,
    displayFeature: SettingsDisplayFeatureViewModel,
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val haptic = LocalAppHapticFeedback.current

    val activeCustomSeed = (uiState.colorSource as? ColorSource.CustomSeed)?.argb
    val initialSeed = activeCustomSeed ?: DEFAULT_CUSTOM_SEED_ARGB
    val initialHsl = remember(initialSeed) { argbToHsl(initialSeed) }

    var hue by rememberSaveable(initialSeed) { mutableFloatStateOf(initialHsl[0]) }
    var saturation by rememberSaveable(initialSeed) { mutableFloatStateOf(initialHsl[1]) }
    var lightness by rememberSaveable(initialSeed) { mutableFloatStateOf(initialHsl[2]) }

    val currentCustomSeedArgb = remember(hue, saturation, lightness) {
        hslToOpaqueArgb(hue, saturation, lightness)
    }

    var showCustomPreview by rememberSaveable(uiState.colorSource) {
        mutableStateOf(uiState.colorSource is ColorSource.CustomSeed)
    }

    val isDark = when (uiState.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val activeScheme = MaterialTheme.colorScheme
    val customPreviewScheme = remember(currentCustomSeedArgb, isDark) {
        colorSchemeFromSeed(currentCustomSeedArgb, isDark)
    }

    val previewScheme = if (showCustomPreview) customPreviewScheme else activeScheme

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            ColorPaletteTopAppBar(
                scrollBehavior = scrollBehavior,
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = AppSpacing.ScreenHorizontalPadding,
                    vertical = AppSpacing.MediumSmall,
                ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
        ) {
            ColorSchemePreviewCard(colorScheme = previewScheme)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                DynamicWallpaperCard(
                    selected = !showCustomPreview && uiState.colorSource is ColorSource.DynamicWallpaper,
                    onSelect = {
                        haptic.medium()
                        showCustomPreview = false
                        displayFeature.updateColorSource(ColorSource.DynamicWallpaper)
                    },
                )
            }
            CustomSeedCard(
                hue = hue,
                onHueChange = { hue = it; showCustomPreview = true },
                saturation = saturation,
                onSaturationChange = { saturation = it; showCustomPreview = true },
                lightness = lightness,
                onLightnessChange = { lightness = it; showCustomPreview = true },
                currentCustomSeedArgb = currentCustomSeedArgb,
                selectedSeed = activeCustomSeed,
                colorHistory = uiState.colorHistory,
                onSelect = { argb ->
                    val hsl = argbToHsl(argb)
                    hue = hsl[0]; saturation = hsl[1]; lightness = hsl[2]
                    showCustomPreview = true
                },
                onApply = { argb ->
                    displayFeature.updateColorSource(ColorSource.CustomSeed(argb))
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorPaletteTopAppBar(
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    onBack: () -> Unit,
) {
    val haptic = LocalAppHapticFeedback.current
    LargeTopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.ColorLens,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(AppSpacing.Medium))
                Text(stringResource(R.string.settings_color_palette))
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
}


@Composable
private fun ColorSchemePreviewCard(
    colorScheme: ColorScheme,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.settings_color_palette_preview_title),
                style = MaterialTheme.typography.labelMedium,
                color = colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(14.dp))
            MockNotePreviewCard(colorScheme = colorScheme)
        }
    }
}

@Composable
private fun MockNotePreviewCard(
    colorScheme: ColorScheme,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Header Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Avatar Circle mockup
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "L",
                        style = MaterialTheme.typography.titleMedium,
                        color = colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Lomo Inspiration",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "10 minutes ago",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
                // Tag Badge mockup
                Surface(
                    shape = CircleShape,
                    color = colorScheme.secondaryContainer,
                    modifier = Modifier.height(24.dp)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Design",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            // Title mockup
            Text(
                text = stringResource(R.string.settings_color_preview_note_title),
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            // Body text mockup
            Text(
                text = stringResource(R.string.settings_color_preview_note_body),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
                lineHeight = 22.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            // Subtle Divider
            androidx.compose.material3.HorizontalDivider(
                color = colorScheme.outlineVariant.copy(alpha = 0.5f),
                thickness = 0.5.dp
            )
            Spacer(modifier = Modifier.height(12.dp))
            // Footer Row
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "284 words",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Text(
                    text = "Edited 2h ago",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun DynamicWallpaperCard(
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onSelect),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_color_source_dynamic),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = stringResource(R.string.settings_color_source_dynamic_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun CustomSeedCard(
    hue: Float,
    onHueChange: (Float) -> Unit,
    saturation: Float,
    onSaturationChange: (Float) -> Unit,
    lightness: Float,
    onLightnessChange: (Float) -> Unit,
    currentCustomSeedArgb: Int,
    selectedSeed: Int?,
    colorHistory: ImmutableList<Int>,
    onSelect: (Int) -> Unit,
    onApply: (Int) -> Unit,
) {
    val active = selectedSeed != null
    val haptic = LocalAppHapticFeedback.current

    var hexInput by remember(currentCustomSeedArgb) {
        mutableStateOf("#%06X".format(currentCustomSeedArgb and COLOR_PICKER_RGB_MASK))
    }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            CustomSeedHeader(
                currentArgb = currentCustomSeedArgb,
                active = active,
                onSelect = onSelect
            )

            Spacer(modifier = Modifier.height(12.dp))

            CustomSeedHexInput(
                hexInput = hexInput,
                onHexInputChanged = { input ->
                    hexInput = input
                    val cleanHex = input.trim().removePrefix("#")
                    if (cleanHex.length == 6) {
                        // behavior-contract: silent-result-ok: parsing malformed/incomplete hex input safely
                        val parsed = runCatching {
                            cleanHex.toLong(16).toInt() or 0xFF000000.toInt()
                        }.getOrNull()
                        if (parsed != null) {
                            onSelect(parsed)
                        }
                    }
                }
            )

            CustomSeedHistory(
                colorHistory = colorHistory,
                selectedSeed = selectedSeed,
                onHistorySelected = onSelect
            )

            Spacer(modifier = Modifier.height(12.dp))
            CustomSeedSliders(
                hue = hue,
                onHueChange = onHueChange,
                saturation = saturation,
                onSaturationChange = onSaturationChange,
                lightness = lightness,
                onLightnessChange = onLightnessChange
            )

            Spacer(modifier = Modifier.height(16.dp))
            androidx.compose.material3.Button(
                onClick = {
                    onApply(currentCustomSeedArgb)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.settings_color_apply_custom))
            }
        }
    }
}

@Composable
private fun CustomSeedHeader(
    currentArgb: Int,
    active: Boolean,
    onSelect: (Int) -> Unit,
) {
    val haptic = LocalAppHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable {
                haptic.medium()
                onSelect(currentArgb)
            }
            .padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(SEED_PREVIEW_SWATCH_DP.dp)
                .clip(CircleShape)
                .background(Color(currentArgb))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                ),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_color_source_custom),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "#%06X".format(currentArgb and COLOR_PICKER_RGB_MASK),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (active) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun CustomSeedHexInput(
    hexInput: String,
    onHexInputChanged: (String) -> Unit,
) {
    OutlinedTextField(
        value = hexInput,
        onValueChange = onHexInputChanged,
        label = { Text(stringResource(R.string.settings_color_picker_hex)) },
        placeholder = { Text("#RRGGBB") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    )
}

@Composable
private fun CustomSeedHistory(
    colorHistory: ImmutableList<Int>,
    selectedSeed: Int?,
    onHistorySelected: (Int) -> Unit,
) {
    if (colorHistory.isEmpty()) return
    val haptic = LocalAppHapticFeedback.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.settings_color_history_section),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            items(colorHistory) { colorHistoryArgb ->
                val historySelected = selectedSeed == colorHistoryArgb
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(colorHistoryArgb))
                        .border(
                            width = if (historySelected) 3.dp else 1.dp,
                            color = if (historySelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = CircleShape,
                        )
                        .clip(CircleShape)
                        .clickable {
                            haptic.medium()
                            onHistorySelected(colorHistoryArgb)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (historySelected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomSeedSliders(
    hue: Float,
    onHueChange: (Float) -> Unit,
    saturation: Float,
    onSaturationChange: (Float) -> Unit,
    lightness: Float,
    onLightnessChange: (Float) -> Unit,
) {
    val haptic = LocalAppHapticFeedback.current
    Column {
        ColorSlider(
            label = stringResource(R.string.settings_color_picker_hue),
            value = hue,
            onValueChange = onHueChange,
            onValueChangeFinished = {
                haptic.medium()
            },
            valueRange = 0f..HUE_MAX,
        )
        Spacer(modifier = Modifier.height(8.dp))
        ColorSlider(
            label = stringResource(R.string.settings_color_picker_saturation),
            value = saturation,
            onValueChange = onSaturationChange,
            onValueChangeFinished = {
                haptic.medium()
            },
            valueRange = 0f..SATURATION_MAX,
        )
        Spacer(modifier = Modifier.height(8.dp))
        ColorSlider(
            label = stringResource(R.string.settings_color_picker_lightness),
            value = lightness,
            onValueChange = onLightnessChange,
            onValueChangeFinished = {
                haptic.medium()
            },
            valueRange = 0f..LIGHTNESS_MAX,
        )
    }
}

@Composable
private fun ColorSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            modifier = Modifier.weight(1f)
        )
    }
}
