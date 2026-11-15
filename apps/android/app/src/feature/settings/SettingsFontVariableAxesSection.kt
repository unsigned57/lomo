package com.lomo.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.app.feature.preferences.CustomFontStatus
import com.lomo.domain.model.CustomFontInfo
import com.lomo.domain.model.CustomFontVariationResolver
import com.lomo.domain.model.FontPreference
import com.lomo.domain.model.FontVariationAxis
import com.lomo.ui.component.common.LomoFilledTonalIconButton
import com.lomo.ui.component.common.LomoTextButton
import com.lomo.ui.component.common.lomoCompactInteractiveHeight
import com.lomo.ui.icon.LomoIcon
import com.lomo.ui.icon.LomoIcons
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.coroutines.delay
import kotlin.math.abs

private const val VARIABLE_PREVIEW_DEBOUNCE_MS = 90L
private const val AXIS_BOUND_EPSILON = 1e-3f
private const val BADGE_CONTAINER_ALPHA = 0.12f

@Stable
internal class VariableFontAxesEditorState(
    val selectedPreference: FontPreference.UserImported?,
    val visibleAxes: List<FontVariationAxis>,
    val showControls: Boolean,
    private val draftAxesState: MutableState<Map<String, Float>>,
    private val livePreviewFontFamilyState: State<FontFamily?>,
    private val onUpdateFontPreference: (FontPreference) -> Unit,
) {
    val draftAxes: Map<String, Float> by draftAxesState
    val livePreviewFontFamily: FontFamily? by livePreviewFontFamilyState

    fun updateDraft(tag: String, value: Float) {
        draftAxesState.value = updateAxisOverride(visibleAxes, draftAxesState.value, tag, value)
    }

    fun commitDraft() {
        val pref = selectedPreference ?: return
        onUpdateFontPreference(
            FontPreference.UserImported(pref.id, draftAxesState.value),
        )
    }

    fun stepCommit(tag: String, value: Float) {
        val pref = selectedPreference ?: return
        val next = updateAxisOverride(visibleAxes, draftAxesState.value, tag, value)
        draftAxesState.value = next
        onUpdateFontPreference(
            FontPreference.UserImported(pref.id, next),
        )
    }

    fun reset() {
        val pref = selectedPreference ?: return
        draftAxesState.value = emptyMap()
        onUpdateFontPreference(
            FontPreference.UserImported(pref.id),
        )
    }
}

@Composable
internal fun rememberVariableFontAxesEditorState(
    selectedPreference: FontPreference.UserImported?,
    availableCustomFonts: List<CustomFontInfo>,
    customFontStatus: CustomFontStatus,
    onUpdateFontPreference: (FontPreference) -> Unit,
    onPreviewVariableFontFamily: suspend (String, Map<String, Float>) -> FontFamily?,
): VariableFontAxesEditorState {
    val selectedCustomFont =
        selectedPreference?.let { pref ->
            availableCustomFonts.firstOrNull { it.id == pref.id }
        }
    val visibleAxes = remember(selectedCustomFont) {
        orderVisibleAxes(selectedCustomFont?.variableAxes.orEmpty())
    }
    val draftAxesState = remember(selectedPreference?.id, visibleAxes) {
        mutableStateOf(
            CustomFontVariationResolver.sanitizeOverrides(
                visibleAxes,
                selectedPreference?.axisOverrides.orEmpty(),
            ),
        )
    }
    LaunchedEffect(selectedPreference, visibleAxes) {
        val persisted =
            CustomFontVariationResolver.sanitizeOverrides(
                visibleAxes,
                selectedPreference?.axisOverrides.orEmpty(),
            )
        if (draftAxesState.value != persisted) {
            draftAxesState.value = persisted
        }
    }
    val draftAxes = draftAxesState.value
    val livePreviewFontFamilyState = produceState<FontFamily?>(
        initialValue = null,
        selectedPreference,
        draftAxes,
        customFontStatus,
    ) {
        if (
            selectedPreference == null ||
            customFontStatus != CustomFontStatus.READY ||
            draftAxes == selectedPreference.axisOverrides
        ) {
            value = null
        } else {
            delay(VARIABLE_PREVIEW_DEBOUNCE_MS)
            value = onPreviewVariableFontFamily(selectedPreference.id, draftAxes)
        }
    }
    val showControls =
        selectedPreference != null &&
            visibleAxes.isNotEmpty() &&
            customFontStatus == CustomFontStatus.READY
    return remember(
        selectedPreference,
        visibleAxes,
        showControls,
        draftAxesState,
        livePreviewFontFamilyState,
        onUpdateFontPreference,
    ) {
        VariableFontAxesEditorState(
            selectedPreference = selectedPreference,
            visibleAxes = visibleAxes,
            showControls = showControls,
            draftAxesState = draftAxesState,
            livePreviewFontFamilyState = livePreviewFontFamilyState,
            onUpdateFontPreference = onUpdateFontPreference,
        )
    }
}

@Composable
internal fun VariableFontAxesSection(editor: VariableFontAxesEditorState) {
    if (!editor.showControls) return
    val haptic = LocalAppHapticFeedback.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.LargeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            VariableFontAxesHeader(
                canReset = editor.draftAxes.isNotEmpty(),
                onReset = {
                    haptic.medium()
                    editor.reset()
                },
            )
            editor.visibleAxes.forEach { axis ->
                val currentValue = axis.clamp(editor.draftAxes[axis.tag] ?: axis.defaultValue)
                VariableFontAxisControlRow(
                    axis = axis,
                    currentValue = currentValue,
                    onDraftChange = { editor.updateDraft(axis.tag, it) },
                    onCommit = {
                        haptic.medium()
                        editor.commitDraft()
                    },
                    onStepCommit = { stepped ->
                        haptic.medium()
                        editor.stepCommit(axis.tag, stepped)
                    },
                )
            }
        }
    }
}

@Composable
private fun VariableFontAxesHeader(canReset: Boolean, onReset: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LomoIcon(
            icon = LomoIcons.Tune,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.settings_font_axes_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        LomoTextButton(
            onClick = onReset,
            enabled = canReset,
        ) {
            LomoIcon(
                icon = LomoIcons.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = stringResource(R.string.settings_font_axes_reset))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VariableFontAxisControlRow(
    axis: FontVariationAxis,
    currentValue: Float,
    onDraftChange: (Float) -> Unit,
    onCommit: () -> Unit,
    onStepCommit: (Float) -> Unit,
) {
    val step = resolveAxisStep(axis)
    val sliderState =
        rememberSliderState(
            value = currentValue,
            trackRange = axis.minValue..axis.maxValue,
        )
    LaunchedEffect(currentValue) {
        if (abs(sliderState.value - currentValue) > AXIS_BOUND_EPSILON) {
            sliderState.value = currentValue
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        VariableFontAxisLabelRow(axis = axis, currentValue = currentValue)
        Row(verticalAlignment = Alignment.CenterVertically) {
            LomoFilledTonalIconButton(
                onClick = { onStepCommit(currentValue - step) },
                enabled = currentValue > axis.minValue + AXIS_BOUND_EPSILON,
                modifier =
                Modifier
                    .lomoCompactInteractiveHeight(36.dp)
                    .size(36.dp),
            ) {
                LomoIcon(
                    icon = LomoIcons.Remove,
                    contentDescription = stringResource(R.string.cd_typography_step_decrement),
                    modifier = Modifier.size(18.dp),
                )
            }
            Slider(
                state = sliderState,
                onValueChange = onDraftChange,
                onValueChangeFinished = onCommit,
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
                onClick = { onStepCommit(currentValue + step) },
                enabled = currentValue < axis.maxValue - AXIS_BOUND_EPSILON,
                modifier =
                Modifier
                    .lomoCompactInteractiveHeight(36.dp)
                    .size(36.dp),
            ) {
                LomoIcon(
                    icon = LomoIcons.Add,
                    contentDescription = stringResource(R.string.cd_typography_step_increment),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatAxisValue(axis.minValue),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.settings_font_axis_default_hint, formatAxisValue(axis.defaultValue)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatAxisValue(axis.maxValue),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VariableFontAxisLabelRow(axis: FontVariationAxis, currentValue: Float) {
    val label =
        when (axis.tag) {
            "wght" -> stringResource(R.string.settings_font_axis_wght)
            "wdth" -> stringResource(R.string.settings_font_axis_wdth)
            "opsz" -> stringResource(R.string.settings_font_axis_opsz)
            "slnt" -> stringResource(R.string.settings_font_axis_slnt)
            "ital" -> stringResource(R.string.settings_font_axis_ital)
            "GRAD" -> stringResource(R.string.settings_font_axis_grad)
            else -> axis.name
        }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label · ${axis.tag}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Surface(
            shape = AppShapes.Small,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Text(
                text = formatAxisValue(currentValue),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
internal fun VariableFontBadge(axisCount: Int, selected: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = BADGE_CONTAINER_ALPHA)
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
    ) {
        Text(
            text = "${stringResource(R.string.settings_font_variable_badge)} · ${
                pluralStringResource(R.plurals.settings_font_variable_axes_count, axisCount, axisCount)
            }",
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
