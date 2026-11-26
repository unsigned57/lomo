package com.lomo.app.feature.settings

import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import com.lomo.app.R
import com.lomo.domain.model.ColorSource
import com.lomo.domain.model.ThemeMode
import com.lomo.ui.component.common.LomoButton
import com.lomo.ui.component.common.LomoIconButton
import com.lomo.ui.icon.LomoIcon
import com.lomo.ui.icon.LomoIcons
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.colorSchemeFromSeed
import com.lomo.ui.theme.resolveLomoColorScheme
import com.lomo.ui.theme.withPureBlackSurfaces
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch

private const val HUE_MAX = 360f
private const val SATURATION_MAX = 1f
private const val LIGHTNESS_MAX = 1f
private const val HSL_COMPONENT_COUNT = 3
private const val SEED_PREVIEW_SWATCH_DP = 52
private const val DEFAULT_CUSTOM_SEED_ARGB: Int = 0xFF4F63D6.toInt()
private const val SATURATION_TRACK_MIN_LIGHTNESS = 0.18f
private const val SATURATION_TRACK_MAX_LIGHTNESS = 0.82f
private const val HUE_SPECTRUM_STOP_DEGREES = 60

private val HUE_SPECTRUM_COLORS: List<Color> =
    (0..HUE_MAX.toInt() step HUE_SPECTRUM_STOP_DEGREES).map { deg ->
        Color(ColorUtils.HSLToColor(floatArrayOf(deg.toFloat(), 1f, 0.5f)))
    }

private data class ColorPaletteEditorSnapshot(
    val previewScheme: ColorScheme,
    val customPreviewScheme: ColorScheme,
    val previewIsDark: Boolean,
    val showCustomPreview: Boolean,
    val currentCustomSeedArgb: Int,
    val activeCustomSeed: Int?,
    val hue: Float,
    val saturation: Float,
    val lightness: Float,
)

private data class ColorPaletteActions(
    val onTogglePreviewDark: () -> Unit,
    val onSelectDynamic: () -> Unit,
    val onSelectPreset: (com.lomo.domain.model.ColorPresetId) -> Unit,
    val onHueChange: (Float) -> Unit,
    val onSaturationChange: (Float) -> Unit,
    val onLightnessChange: (Float) -> Unit,
    val onRandomize: () -> Unit,
    val onSelectCustomSeed: (Int) -> Unit,
    val onApplyCustomSeed: (Int) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColorPaletteSettingsPage(
    uiState: DisplaySectionState,
    displayFeature: SettingsDisplayFeatureViewModel,
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val (editor, actions) = rememberColorPaletteEditor(
        uiState = uiState,
        onUpdateColorSource = displayFeature::updateColorSource,
    )

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SettingsPageTopAppBar(
                title = stringResource(R.string.settings_color_palette),
                scrollBehavior = scrollBehavior,
                onBack = onBack,
            )
        },
    ) { padding ->
        ColorPaletteSettingsBody(
            modifier = Modifier.padding(padding),
            uiState = uiState,
            editor = editor,
            actions = actions,
            onSelectContrastLevel = displayFeature.updateContrastLevel,
        )
    }
}

@Composable
private fun rememberColorPaletteEditor(
    uiState: DisplaySectionState,
    onUpdateColorSource: (ColorSource) -> Unit,
): Pair<ColorPaletteEditorSnapshot, ColorPaletteActions> {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val effectiveIsDark =
        when (uiState.themeMode) {
            ThemeMode.SYSTEM -> systemDark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
    var previewDarkOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val previewIsDark = previewDarkOverride ?: effectiveIsDark
    val activeCustomSeed = (uiState.colorSource as? ColorSource.CustomSeed)?.argb
    val initialSeed = activeCustomSeed ?: DEFAULT_CUSTOM_SEED_ARGB
    val initialHsl = remember(initialSeed) {
        FloatArray(HSL_COMPONENT_COUNT).also { ColorUtils.colorToHSL(initialSeed, it) }
    }
    var hue by rememberSaveable(initialSeed) { mutableFloatStateOf(initialHsl[0]) }
    var saturation by rememberSaveable(initialSeed) { mutableFloatStateOf(initialHsl[1]) }
    var lightness by rememberSaveable(initialSeed) { mutableFloatStateOf(initialHsl[2]) }
    var hexSyncedSeed by rememberSaveable(initialSeed) { mutableStateOf<Int?>(initialSeed) }
    val hslDerivedArgb = remember(hue, saturation, lightness) {
        ColorUtils.HSLToColor(floatArrayOf(hue, saturation, lightness))
    }
    val currentCustomSeedArgb = hexSyncedSeed ?: hslDerivedArgb
    var showCustomPreview by rememberSaveable(uiState.colorSource) {
        mutableStateOf(uiState.colorSource is ColorSource.CustomSeed)
    }
    val customPreviewScheme =
        remember(currentCustomSeedArgb, previewIsDark, uiState.contrastLevel, uiState.pureBlackDark) {
            colorSchemeFromSeed(currentCustomSeedArgb, previewIsDark, uiState.contrastLevel)
                .withPureBlackSurfaces(isDark = previewIsDark, enabled = uiState.pureBlackDark)
        }
    val activePreviewScheme = remember(
        context,
        uiState.colorSource,
        previewIsDark,
        uiState.contrastLevel,
        uiState.pureBlackDark,
    ) {
        resolveLomoColorScheme(context, uiState.colorSource, previewIsDark, uiState.contrastLevel)
            .withPureBlackSurfaces(isDark = previewIsDark, enabled = uiState.pureBlackDark)
    }
    val snapshot = ColorPaletteEditorSnapshot(
        previewScheme = if (showCustomPreview) customPreviewScheme else activePreviewScheme,
        customPreviewScheme = customPreviewScheme,
        previewIsDark = previewIsDark,
        showCustomPreview = showCustomPreview,
        currentCustomSeedArgb = currentCustomSeedArgb,
        activeCustomSeed = activeCustomSeed,
        hue = hue,
        saturation = saturation,
        lightness = lightness,
    )
    val actions = rememberColorPaletteActions(
        onUpdateColorSource = onUpdateColorSource,
        previewIsDark = previewIsDark,
        onPreviewDarkOverride = { previewDarkOverride = it },
        onShowCustomPreview = { showCustomPreview = it },
        onHexSyncedSeed = { hexSyncedSeed = it },
        onHue = { hue = it },
        onSaturation = { saturation = it },
        onLightness = { lightness = it },
    )
    return snapshot to actions
}

@Composable
private fun rememberColorPaletteActions(
    onUpdateColorSource: (ColorSource) -> Unit,
    previewIsDark: Boolean,
    onPreviewDarkOverride: (Boolean) -> Unit,
    onShowCustomPreview: (Boolean) -> Unit,
    onHexSyncedSeed: (Int?) -> Unit,
    onHue: (Float) -> Unit,
    onSaturation: (Float) -> Unit,
    onLightness: (Float) -> Unit,
): ColorPaletteActions = ColorPaletteActions(
    onTogglePreviewDark = { onPreviewDarkOverride(!previewIsDark) },
    onSelectDynamic = {
        onShowCustomPreview(false)
        onUpdateColorSource(ColorSource.DynamicWallpaper)
    },
    onSelectPreset = { preset ->
        onShowCustomPreview(false)
        onUpdateColorSource(ColorSource.Preset(preset))
    },
    onHueChange = {
        onHexSyncedSeed(null)
        onHue(it)
        onShowCustomPreview(true)
    },
    onSaturationChange = {
        onHexSyncedSeed(null)
        onSaturation(it)
        onShowCustomPreview(true)
    },
    onLightnessChange = {
        onHexSyncedSeed(null)
        onLightness(it)
        onShowCustomPreview(true)
    },
    onRandomize = {
        val randomHsl = generateRandomSeedHsl()
        onHexSyncedSeed(null)
        onHue(randomHsl[0])
        onSaturation(randomHsl[1])
        onLightness(randomHsl[2])
        onShowCustomPreview(true)
    },
    onSelectCustomSeed = { argb ->
        val hsl = FloatArray(HSL_COMPONENT_COUNT)
        ColorUtils.colorToHSL(argb, hsl)
        onHexSyncedSeed(argb)
        onHue(hsl[0])
        onSaturation(hsl[1])
        onLightness(hsl[2])
        onShowCustomPreview(true)
    },
    onApplyCustomSeed = { argb ->
        onShowCustomPreview(false)
        onUpdateColorSource(ColorSource.CustomSeed(argb))
    },
)

@Composable
private fun ColorPaletteSettingsBody(
    uiState: DisplaySectionState,
    editor: ColorPaletteEditorSnapshot,
    actions: ColorPaletteActions,
    onSelectContrastLevel: (com.lomo.domain.model.ContrastLevel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalAppHapticFeedback.current
    val contrastAdjustable = isContrastAdjustable(uiState.colorSource, editor.showCustomPreview, Build.VERSION.SDK_INT)
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppSpacing.ScreenHorizontalPadding, vertical = AppSpacing.MediumSmall),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
    ) {
        ColorSchemePreviewCard(editor.previewScheme, editor.previewIsDark, actions.onTogglePreviewDark)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            DynamicWallpaperCard(
                selected = !editor.showCustomPreview && uiState.colorSource is ColorSource.DynamicWallpaper,
                isDarkPreview = editor.previewIsDark,
                pureBlackDark = uiState.pureBlackDark,
                onSelect = {
                    haptic.medium()
                    actions.onSelectDynamic()
                },
            )
        }
        CuratedPresetsCard(
            selectedPreset = if (!editor.showCustomPreview) (uiState.colorSource as? ColorSource.Preset)?.id else null,
            isDarkPreview = editor.previewIsDark,
            contrastLevel = uiState.contrastLevel,
            pureBlackDark = uiState.pureBlackDark,
            onSelectPreset = actions.onSelectPreset,
        )
        CustomSeedCard(
            hue = editor.hue,
            onHueChange = actions.onHueChange,
            saturation = editor.saturation,
            onSaturationChange = actions.onSaturationChange,
            lightness = editor.lightness,
            onLightnessChange = actions.onLightnessChange,
            onRandomize = actions.onRandomize,
            currentCustomSeedArgb = editor.currentCustomSeedArgb,
            activeColorSource = uiState.colorSource,
            previewScheme = editor.customPreviewScheme,
            selectedHistorySeed =
            if (editor.showCustomPreview) editor.currentCustomSeedArgb else editor.activeCustomSeed,
            colorHistory = uiState.colorHistory,
            onSelect = actions.onSelectCustomSeed,
            onApply = actions.onApplyCustomSeed,
        )
        ContrastLevelCard(
            selected = uiState.contrastLevel,
            onSelect = { level ->
                haptic.medium()
                onSelectContrastLevel(level)
            },
            enabled = contrastAdjustable,
            disabledHint = if (!contrastAdjustable) {
                stringResource(R.string.settings_color_contrast_dynamic_hint)
            } else {
                null
            },
        )
    }
}

@RequiresApi(Build.VERSION_CODES.S)
@Composable
private fun DynamicWallpaperCard(
    selected: Boolean,
    isDarkPreview: Boolean,
    pureBlackDark: Boolean,
    onSelect: () -> Unit,
) {
    val context = LocalContext.current
    val dynamicScheme = remember(context, isDarkPreview, pureBlackDark) {
        (if (isDarkPreview) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context))
            .withPureBlackSurfaces(isDark = isDarkPreview, enabled = pureBlackDark)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.LargeIncreased)
            .clickable(onClick = onSelect),
        shape = AppShapes.LargeIncreased,
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
            MaterialYouThreeToneSwatch(
                primary = dynamicScheme.primary,
                primaryContainer = dynamicScheme.primaryContainer,
                tertiary = dynamicScheme.tertiary,
                selected = selected,
                checkContainerColor = dynamicScheme.primary,
                checkTint = dynamicScheme.onPrimary,
                size = ColorDynamicSwatchSize,
            )
            Spacer(modifier = Modifier.width(14.dp))
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
                LomoIcon(
                    icon = LomoIcons.AutoAwesome,
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
    onRandomize: () -> Unit,
    currentCustomSeedArgb: Int,
    activeColorSource: ColorSource,
    previewScheme: ColorScheme,
    selectedHistorySeed: Int?,
    colorHistory: ImmutableList<Int>,
    onSelect: (Int) -> Unit,
    onApply: (Int) -> Unit,
) {
    val alreadyApplied = isCustomSeedAlreadyApplied(activeColorSource, currentCustomSeedArgb)
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val hexLabel = stringResource(R.string.settings_color_picker_hex)
    val hexText = remember(currentCustomSeedArgb) { formatHexColor(currentCustomSeedArgb) }
    val copiedToastMessage = stringResource(R.string.settings_color_hex_copied, hexText)

    Card(
        shape = AppShapes.LargeIncreased,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            CustomSeedHeader(
                currentArgb = currentCustomSeedArgb,
                hexText = hexText,
                applied = alreadyApplied,
                onSelect = { onSelect(currentCustomSeedArgb) },
                onCopyHex = {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(hexLabel, hexText))) }
                    Toast.makeText(context, copiedToastMessage, Toast.LENGTH_SHORT).show()
                },
                onRandomize = onRandomize,
            )
            Spacer(modifier = Modifier.height(14.dp))
            CustomSeedHexInput(currentCustomSeedArgb = currentCustomSeedArgb, onValidSeed = onSelect)
            CustomSeedHistory(
                colorHistory = colorHistory,
                selectedSeed = selectedHistorySeed,
                onHistorySelected = onSelect,
            )
            Spacer(modifier = Modifier.height(14.dp))
            CustomSeedSliders(
                hue = hue,
                onHueChange = onHueChange,
                saturation = saturation,
                onSaturationChange = onSaturationChange,
                lightness = lightness,
                onLightnessChange = onLightnessChange,
                currentCustomSeedArgb = currentCustomSeedArgb,
            )
            Spacer(modifier = Modifier.height(16.dp))
            LomoButton(
                onClick = { onApply(currentCustomSeedArgb) },
                enabled = !alreadyApplied,
                colors = ButtonDefaults.buttonColors(
                    containerColor = previewScheme.primary,
                    contentColor = previewScheme.onPrimary,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                LomoIcon(icon = LomoIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        if (alreadyApplied) R.string.settings_color_applied else R.string.settings_color_apply_custom,
                    ),
                )
            }
        }
    }
}

@Composable
private fun CustomSeedHeader(
    currentArgb: Int,
    hexText: String,
    applied: Boolean,
    onSelect: () -> Unit,
    onCopyHex: () -> Unit,
    onRandomize: () -> Unit,
) {
    val haptic = LocalAppHapticFeedback.current
    val checkTint = if (shouldUseDarkContentOnColor(currentArgb)) Color.Black else Color.White
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.Large)
            .clickable {
                haptic.medium()
                onSelect()
            }
            .padding(vertical = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(SEED_PREVIEW_SWATCH_DP.dp)
                .clip(CircleShape)
                .background(Color(currentArgb))
                .border(
                    width = if (applied) 2.5.dp else 1.dp,
                    color = if (applied) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (applied) {
                LomoIcon(
                    icon = LomoIcons.Check,
                    contentDescription = null,
                    tint = checkTint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_color_source_custom),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = hexText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LomoIconButton(
            onClick = {
                haptic.medium()
                onCopyHex()
            },
        ) {
            LomoIcon(
                icon = LomoIcons.ContentCopy,
                contentDescription = stringResource(R.string.settings_color_copy_hex_cd),
            )
        }
        LomoIconButton(
            onClick = {
                haptic.medium()
                onRandomize()
            },
        ) {
            LomoIcon(
                icon = LomoIcons.Shuffle,
                contentDescription = stringResource(R.string.settings_color_random_cd),
            )
        }
    }
}

@Composable
private fun CustomSeedHexInput(currentCustomSeedArgb: Int, onValidSeed: (Int) -> Unit) {
    var hexInput by remember { mutableStateOf(formatHexColor(currentCustomSeedArgb)) }
    LaunchedEffect(currentCustomSeedArgb) {
        val currentParse = parseHexColorInput(hexInput)
        if (currentParse !is HexColorParseResult.Valid || currentParse.argb != currentCustomSeedArgb) {
            hexInput = formatHexColor(currentCustomSeedArgb)
        }
    }
    val parseResult = parseHexColorInput(hexInput)
    val isInvalid = parseResult is HexColorParseResult.Invalid

    OutlinedTextField(
        value = hexInput,
        onValueChange = { input ->
            hexInput = input
            val parsed = parseHexColorInput(input)
            if (parsed is HexColorParseResult.Valid) {
                onValidSeed(parsed.argb)
            }
        },
        label = { Text(stringResource(R.string.settings_color_picker_hex)) },
        placeholder = { Text(stringResource(R.string.settings_color_picker_hex_placeholder)) },
        isError = isInvalid,
        supportingText =
        if (isInvalid) {
            { Text(stringResource(R.string.settings_color_hex_invalid)) }
        } else {
            null
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Medium,
    )
}

@Composable
private fun CustomSeedHistory(colorHistory: ImmutableList<Int>, selectedSeed: Int?, onHistorySelected: (Int) -> Unit) {
    if (colorHistory.isEmpty()) return
    val haptic = LocalAppHapticFeedback.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.settings_color_history_section),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            items(colorHistory) { colorHistoryArgb ->
                val historySelected =
                    selectedSeed != null &&
                        (selectedSeed and COLOR_PICKER_RGB_MASK) == (colorHistoryArgb and COLOR_PICKER_RGB_MASK)
                val checkTint = if (shouldUseDarkContentOnColor(colorHistoryArgb)) Color.Black else Color.White
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(colorHistoryArgb))
                        .border(
                            width = if (historySelected) 2.5.dp else 1.dp,
                            color = if (historySelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = CircleShape,
                        )
                        .clickable {
                            haptic.medium()
                            onHistorySelected(colorHistoryArgb)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (historySelected) {
                        LomoIcon(
                            icon = LomoIcons.Check,
                            contentDescription = null,
                            tint = checkTint,
                            modifier = Modifier.size(18.dp),
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
    currentCustomSeedArgb: Int,
) {
    val haptic = LocalAppHapticFeedback.current
    val clampedTrackLightness = lightness.coerceIn(SATURATION_TRACK_MIN_LIGHTNESS, SATURATION_TRACK_MAX_LIGHTNESS)
    val saturationTrackColors = remember(hue, clampedTrackLightness) {
        listOf(
            Color(ColorUtils.HSLToColor(floatArrayOf(hue, 0f, clampedTrackLightness))),
            Color(ColorUtils.HSLToColor(floatArrayOf(hue, 1f, clampedTrackLightness))),
        )
    }
    val lightnessTrackColors = remember(hue, saturation) {
        listOf(
            Color.Black,
            Color(ColorUtils.HSLToColor(floatArrayOf(hue, saturation, 0.5f))),
            Color.White,
        )
    }
    val hueThumbColor = remember(hue) {
        Color(ColorUtils.HSLToColor(floatArrayOf(hue, 1f, 0.5f)))
    }
    val seedColor = Color(currentCustomSeedArgb)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ColorGradientSlider(
            label = stringResource(R.string.settings_color_picker_hue),
            valueText = formatHueDegrees(hue),
            value = hue,
            valueRange = 0f..HUE_MAX,
            trackColors = HUE_SPECTRUM_COLORS,
            thumbColor = hueThumbColor,
            onValueChange = onHueChange,
            onValueChangeFinished = { haptic.medium() },
        )
        ColorGradientSlider(
            label = stringResource(R.string.settings_color_picker_saturation),
            valueText = formatPercent(saturation),
            value = saturation,
            valueRange = 0f..SATURATION_MAX,
            trackColors = saturationTrackColors,
            thumbColor = seedColor,
            onValueChange = onSaturationChange,
            onValueChangeFinished = { haptic.medium() },
        )
        ColorGradientSlider(
            label = stringResource(R.string.settings_color_picker_lightness),
            valueText = formatPercent(lightness),
            value = lightness,
            valueRange = 0f..LIGHTNESS_MAX,
            trackColors = lightnessTrackColors,
            thumbColor = seedColor,
            onValueChange = onLightnessChange,
            onValueChangeFinished = { haptic.medium() },
        )
    }
}
