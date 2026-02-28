package com.lomo.ui.component.input

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing

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
) {
    val minutes = (state.recordingDuration / 1000) / 60
    val seconds = (state.recordingDuration / 1000) % 60

    Column(
        modifier = Modifier.fillMaxWidth(),
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
            text = String.format("%02d:%02d", minutes, seconds),
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(AppSpacing.Large))

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        AppShapes.Medium,
                    ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .width((50 + (state.recordingAmplitude.coerceIn(0, 32767) / 32767f) * 200).dp)
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.primary, AppShapes.ExtraSmall),
            )
        }

        Spacer(modifier = Modifier.height(AppSpacing.Large))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = callbacks.onCancel,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription =
                        androidx.compose.ui.res.stringResource(
                            com.lomo.ui.R.string.cd_cancel_recording,
                        ),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp),
                )
            }

            androidx.compose.material3.FilledIconButton(
                onClick = callbacks.onStop,
                modifier = Modifier.size(72.dp),
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
                    modifier = Modifier.size(36.dp),
                )
            }
        }
    }
}
