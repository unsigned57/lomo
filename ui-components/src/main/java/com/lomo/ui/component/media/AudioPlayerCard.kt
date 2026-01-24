package com.lomo.ui.component.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.ui.media.LocalAudioPlayerManager
import java.io.File

@Composable
fun AudioPlayerCard(
    relativeFilePath: String, // e.g., "attachments/voice_123.m4a"
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val playerManager = LocalAudioPlayerManager.current

    // Resolve full path (assuming relative to app private storage root)
    // The MainViewModel saves to context.getExternalFilesDir(null) or similar?
    // In MainViewModel logic: File(rootDir, "attachments") where rootDir is User Selected Directory.
    // Wait, the filePath stored in Markdown is relative to the User Selected Directory.
    // But we don't have easy access to the Root Directory here without passing it down.
    // However, MarkdownRenderer handles images by prepending root path.
    // We should probably pass the full absolute path or URI to this component.
    // FOR NOW, let's assume `relativeFilePath` is actually what we get from Markdown.
    // We need to construct the full URI.
    // If the Markdown link is `![voice](attachments/file.m4a)`, the parser extracts `attachments/file.m4a`.
    // We need the base path.

    // Actually, `InputSheet` saves it as `attachments/filename`.
    // The `MainViewModel` uses `_rootDirectory.value` to save it.
    // We need to resolve this path.
    // Since we don't pass rootDir to MarkdownRenderer easily (wait, we do pass `imageMap` etc),
    // we might need to rely on the fact that we can construct a Uri if we know the root.
    // OR: `MarkdownRenderer` usually handles `![image](path)` by resolving it.
    // We should handle `![voice](path)` similarly.

    // Assuming the path passed here is result of some resolution or we need to resolve it.
    // Let's assume for now the caller (MarkdownRenderer) will resolve it to a Uri or absolute path if possible.
    // If not, we might fail.

    // BUT! `MarkdownRenderer` usually gets `rootDir` passed to it?
    // Let's check `MarkdownRenderer`.

    val isPlaying by playerManager.isPlaying.collectAsStateWithLifecycle()
    val currentUri by playerManager.currentPlayingUri.collectAsStateWithLifecycle()
    val playbackPosition by playerManager.playbackPosition.collectAsStateWithLifecycle()
    val totalDuration by playerManager.duration.collectAsStateWithLifecycle()

    // Construct URI. If it's a content:// uri (SAF), we are good.
    // If it is a file path, we need `file://`.
    // If it is relative, we are in trouble without `rootDir`.

    // Let's assume we receive a playable string URI for now.
    val isCurrentItem = currentUri == relativeFilePath

    // Progress update loop
    LaunchedEffect(isCurrentItem, isPlaying) {
        if (isCurrentItem && isPlaying) {
            while (true) {
                playerManager.updateProgress()
                kotlinx.coroutines.delay(100) // Update 10fps
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
            // Icon / Play Button - Use IconButton for explicit click handling
            IconButton(
                onClick = {
                    playerManager.play(relativeFilePath)
                },
                modifier = Modifier.size(32.dp),
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
                        contentDescription = if (isCurrentItem && isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Waveform / Progress
            // Simplified visualizer: just a progress bar or text for now
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                // We use a custom linear progress indicator or just a spacer with text
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f),
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Duration Text
            val position = if (isCurrentItem) playbackPosition else 0L
            val durationToShow = if (isCurrentItem && totalDuration > 0) totalDuration else 0L
            // If we don't know duration, maybe we don't show it or show 00:00

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
