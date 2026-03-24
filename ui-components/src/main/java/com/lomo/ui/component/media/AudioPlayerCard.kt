package com.lomo.ui.component.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.ui.R
import com.lomo.ui.component.common.ExpressiveContainedLoadingIndicator
import com.lomo.ui.media.AudioPlayerController
import com.lomo.ui.media.LocalAudioPlayerManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.util.Locale

private const val AUDIO_TIME_MILLIS_PER_SECOND = 1000
private const val AUDIO_TIME_SECONDS_PER_MINUTE = 60

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun AudioPlayerCard(
    relativeFilePath: String,
    modifier: Modifier = Modifier,
) {
    val playerManager = LocalAudioPlayerManager.current
    val playDescription = stringResource(R.string.cd_play_audio)
    val pauseDescription = stringResource(R.string.cd_pause_audio)
    val playbackState = rememberAudioPlaybackState(playerManager, relativeFilePath)

    val isCurrentItem = playbackState.isCurrentItem
    val isPlaying = playbackState.isPlaying
    val progress = playbackState.progress

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .height(56.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlaybackControlButton(
                isPlaying = isCurrentItem && isPlaying,
                playDescription = playDescription,
                pauseDescription = pauseDescription,
                onClick = { playerManager.play(relativeFilePath) },
            )

            Spacer(modifier = Modifier.width(12.dp))

            PlaybackProgressIndicator(progress = progress)

            Spacer(modifier = Modifier.width(12.dp))
            PlaybackTimestamp(positionMs = if (isCurrentItem) playbackState.positionMs else 0L)
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
private fun rememberAudioPlaybackState(
    playerManager: AudioPlayerController,
    relativeFilePath: String,
): AudioPlaybackState {
    val playbackState by
        remember(playerManager, relativeFilePath) {
            playerManager.currentPlayingUri
                .flatMapLatest { currentUri ->
                    if (currentUri == relativeFilePath) {
                        combine(
                            playerManager.isPlaying,
                            playerManager.playbackPosition,
                            playerManager.duration,
                        ) { isPlaying, position, duration ->
                            AudioPlaybackState(
                                isCurrentItem = true,
                                isPlaying = isPlaying,
                                positionMs = position,
                                durationMs = duration,
                            )
                        }
                    } else {
                        flowOf(AudioPlaybackState())
                    }
                }.distinctUntilChanged()
        }.collectAsStateWithLifecycle(initialValue = AudioPlaybackState())
    return playbackState
}

@Composable
private fun PlaybackControlButton(
    isPlaying: Boolean,
    playDescription: String,
    pauseDescription: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(32.dp)
                    .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) pauseDescription else playDescription,
                tint = MaterialTheme.colorScheme.onSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun RowScope.PlaybackProgressIndicator(progress: Float) {
    Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.Center,
    ) {
        ExpressiveContainedLoadingIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(16.dp),
            indicatorColor = MaterialTheme.colorScheme.secondary,
            containerColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f),
            shape = RoundedCornerShape(2.dp),
        )
    }
}

@Composable
private fun PlaybackTimestamp(positionMs: Long) {
    val totalSeconds = positionMs / AUDIO_TIME_MILLIS_PER_SECOND
    val minutes = totalSeconds / AUDIO_TIME_SECONDS_PER_MINUTE
    val seconds = totalSeconds % AUDIO_TIME_SECONDS_PER_MINUTE

    Text(
        text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private data class AudioPlaybackState(
    val isCurrentItem: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
) {
    val progress: Float
        get() =
            if (isCurrentItem && durationMs > 0) {
                positionMs.toFloat() / durationMs.toFloat()
            } else {
                0f
            }
}
