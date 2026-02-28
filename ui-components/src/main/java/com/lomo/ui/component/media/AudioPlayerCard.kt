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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.ui.R
import com.lomo.ui.media.LocalAudioPlayerManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun AudioPlayerCard(
    relativeFilePath: String,
    modifier: Modifier = Modifier,
) {
    val playerManager = LocalAudioPlayerManager.current
    val playDescription = stringResource(R.string.cd_play_audio)
    val pauseDescription = stringResource(R.string.cd_pause_audio)

    val isPlaying by playerManager.isPlaying.collectAsStateWithLifecycle()
    val currentUri by playerManager.currentPlayingUri.collectAsStateWithLifecycle()
    val playbackPosition by playerManager.playbackPosition.collectAsStateWithLifecycle()
    val totalDuration by playerManager.duration.collectAsStateWithLifecycle()

    val isCurrentItem = currentUri == relativeFilePath

    LaunchedEffect(isCurrentItem, isPlaying) {
        if (isCurrentItem && isPlaying) {
            while (currentCoroutineContext().isActive) {
                playerManager.updateProgress()
                delay(100)
            }
        }
    }

    val progress =
        if (isCurrentItem && totalDuration > 0) {
            playbackPosition.toFloat() / totalDuration.toFloat()
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

            val position = if (isCurrentItem) playbackPosition else 0L
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
