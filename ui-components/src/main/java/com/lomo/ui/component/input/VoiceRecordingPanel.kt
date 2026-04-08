package com.lomo.ui.component.input

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.unit.dp
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing

private const val MILLIS_PER_SECOND = 1000
private const val SECONDS_PER_MINUTE = 60
private const val MIN_RECORDING_AMPLITUDE = 0
private const val MAX_RECORDING_AMPLITUDE = 32767
private const val MIN_WAVEFORM_WIDTH = 50
private const val WAVEFORM_WIDTH_RANGE = 200
private val WAVEFORM_TRACK_HEIGHT = 48.dp
private val WAVEFORM_HEIGHT = 4.dp
private val CANCEL_BUTTON_SIZE = 56.dp
private val CANCEL_ICON_SIZE = 32.dp
private val STOP_BUTTON_SIZE = 72.dp
private val STOP_ICON_SIZE = 36.dp

data class VoiceRecordingPanelState(
    val recordingDuration: Long = 0L,
    val recordingAmplitude: Int = 0,
)

data class VoiceRecordingPanelCallbacks(
    val onCancel: () -> Unit,
    val onStop: () -> Unit,
)

@Composable
fun VoiceRecordingPanel(
    state: VoiceRecordingPanelState,
    callbacks: VoiceRecordingPanelCallbacks,
    modifier: Modifier = Modifier,
) {
    val totalSeconds = state.recordingDuration / MILLIS_PER_SECOND
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    val waveformWidth = calculateWaveformWidth(state.recordingAmplitude)
    val locale = LocalLocale.current.platformLocale

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = AppSpacing.Small),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text =
                androidx.compose.ui.res
                    .stringResource(com.lomo.ui.R.string.recording_in_progress),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(AppSpacing.Medium))

        Text(
            text = String.format(locale, "%02d:%02d", minutes, seconds),
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(AppSpacing.Large))

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(WAVEFORM_TRACK_HEIGHT)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        AppShapes.Medium,
                    ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            RecordingAmplitudeBar(width = waveformWidth)
        }

        Spacer(modifier = Modifier.height(AppSpacing.Large))

        VoiceRecordingControls(callbacks = callbacks)
    }
}

@Composable
private fun RecordingAmplitudeBar(width: Float) {
    Box(
        modifier =
            Modifier
                .width(width.dp)
                .height(WAVEFORM_HEIGHT)
                .background(MaterialTheme.colorScheme.primary, AppShapes.ExtraSmall),
    )
}

@Composable
private fun VoiceRecordingControls(callbacks: VoiceRecordingPanelCallbacks) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = callbacks.onCancel,
            modifier = Modifier.size(CANCEL_BUTTON_SIZE),
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription =
                    androidx.compose.ui.res.stringResource(
                        com.lomo.ui.R.string.cd_cancel_recording,
                    ),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(CANCEL_ICON_SIZE),
            )
        }

        androidx.compose.material3.FilledIconButton(
            onClick = callbacks.onStop,
            modifier = Modifier.size(STOP_BUTTON_SIZE),
            colors =
                androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
        ) {
            Icon(
                Icons.Rounded.Stop,
                contentDescription =
                    androidx.compose.ui.res.stringResource(
                        com.lomo.ui.R.string.cd_stop_recording,
                    ),
                modifier = Modifier.size(STOP_ICON_SIZE),
            )
        }
    }
}

private fun calculateWaveformWidth(recordingAmplitude: Int): Float {
    val normalizedAmplitude =
        recordingAmplitude
            .coerceIn(MIN_RECORDING_AMPLITUDE, MAX_RECORDING_AMPLITUDE)
            .toFloat() / MAX_RECORDING_AMPLITUDE.toFloat()
    return MIN_WAVEFORM_WIDTH + (normalizedAmplitude * WAVEFORM_WIDTH_RANGE)
}
