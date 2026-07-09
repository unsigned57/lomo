package com.lomo.ui.media

import kotlinx.coroutines.flow.StateFlow

interface AudioPlayerController {
    val currentPlayingUri: StateFlow<String?>
    val isPlaying: StateFlow<Boolean>
    val playbackPosition: StateFlow<Long>
    val duration: StateFlow<Long>

    fun play(uri: String)

    fun seekTo(positionMs: Long)

    fun pause()

    fun stop()

    fun release()

    fun updateProgress()
}
