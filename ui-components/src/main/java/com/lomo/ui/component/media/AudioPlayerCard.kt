package com.lomo.ui.component.media

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.ui.R
import com.lomo.ui.media.LocalAudioPlayerManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun AudioPlayerCard(
    relativeFilePath: String,
    modifier: Modifier = Modifier,
) {
    val playerManager = LocalAudioPlayerManager.current
    val playDescription = stringResource(R.string.cd_play_audio)
    val pauseDescription = stringResource(R.string.cd_pause_audio)

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

    val isCurrentItem = playbackState.isCurrentItem
    val isPlaying = playbackState.isPlaying

    LaunchedEffect(isCurrentItem, isPlaying) {
        if (isCurrentItem && isPlaying) {
            while (currentCoroutineContext().isActive) {
                playerManager.updateProgress()
                delay(100)
            }
        }
    }

    val progress =
        if (isCurrentItem && playbackState.durationMs > 0) {
            playbackState.positionMs.toFloat() / playbackState.durationMs.toFloat()
        } else {
            0f
        }

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
            IconButton(
                onClick = {
                    playerManager.play(relativeFilePath)
                },
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
                        imageVector = if (isCurrentItem && isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isCurrentItem && isPlaying) pauseDescription else playDescription,
                        tint = MaterialTheme.colorScheme.onSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f),
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            val position = if (isCurrentItem) playbackState.positionMs else 0L
            val minutes = (position / 1000) / 60
            val seconds = (position / 1000) % 60

            Text(
                text = String.format("%02d:%02d", minutes, seconds),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class AudioPlaybackState(
    val isCurrentItem: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)
