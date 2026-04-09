package com.lomo.ui.component.media

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.ui.R
import com.lomo.ui.media.AudioPlayerController
import com.lomo.ui.media.LocalAudioPlayerManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

private const val AUDIO_TIME_MILLIS_PER_SECOND = 1000
private const val AUDIO_TIME_SECONDS_PER_MINUTE = 60
private const val AUDIO_PLAYBACK_PROGRESS_TAG = "audio_playback_progress"
private const val AUDIO_PROGRESS_ANIMATION_DURATION_MS = 180

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
    val durationMs = playbackState.durationMs
    val animatedProgress by
        animateFloatAsState(
            targetValue = progress.coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = AUDIO_PROGRESS_ANIMATION_DURATION_MS),
            label = "audioPlaybackProgress",
        )
    val displayProgress = animatedProgress.coerceIn(0f, 1f)

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

            PlaybackProgressIndicator(
                progress = displayProgress,
                active = isCurrentItem && durationMs > 0L,
            )

            Spacer(modifier = Modifier.width(12.dp))
            PlaybackTimestamp(
                positionMs = if (isCurrentItem) playbackState.positionMs else 0L,
                durationMs = if (isCurrentItem) durationMs else 0L,
            )
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RowScope.PlaybackProgressIndicator(
    progress: Float,
    active: Boolean,
) {
    Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.Center,
    ) {
        LinearWavyProgressIndicator(
            progress = { progress },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .testTag(AUDIO_PLAYBACK_PROGRESS_TAG),
            amplitude = { 0.12f + (it * 0.08f) },
            wavelength = 28.dp,
            waveSpeed = 18.dp,
            color =
                if (active) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
            trackColor =
                if (active) {
                    MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.22f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)
                },
        )
    }
}

@Composable
private fun PlaybackTimestamp(
    positionMs: Long,
    durationMs: Long,
) {
    val locale = LocalLocale.current.platformLocale

    Text(
        text = "${formatAudioTimestamp(positionMs, locale)} / ${formatAudioTimestamp(durationMs, locale)}",
        modifier = Modifier.widthIn(min = 84.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
    )
}

private fun formatAudioTimestamp(
    positionMs: Long,
    locale: java.util.Locale,
): String {
    val totalSeconds = positionMs / AUDIO_TIME_MILLIS_PER_SECOND
    val minutes = totalSeconds / AUDIO_TIME_SECONDS_PER_MINUTE
    val seconds = totalSeconds % AUDIO_TIME_SECONDS_PER_MINUTE
    return String.format(locale, "%02d:%02d", minutes, seconds)
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
