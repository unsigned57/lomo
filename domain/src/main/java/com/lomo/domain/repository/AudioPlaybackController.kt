package com.lomo.domain.repository

import com.lomo.domain.model.StorageLocation
import kotlinx.coroutines.flow.StateFlow

interface AudioPlaybackController {
    val currentPlayingUri: StateFlow<String?>
    val isPlaying: StateFlow<Boolean>
    val playbackPosition: StateFlow<Long>
    val duration: StateFlow<Long>

    fun setRootLocation(location: StorageLocation?)

    fun setVoiceLocation(location: StorageLocation?)

    fun play(uri: String)

    fun seekTo(positionMs: Long)

    fun pause()

    fun stop()

    fun release()

    fun updateProgress()
}
