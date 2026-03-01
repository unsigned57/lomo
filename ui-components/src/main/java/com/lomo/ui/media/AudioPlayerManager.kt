package com.lomo.ui.media

import androidx.compose.runtime.staticCompositionLocalOf
import com.lomo.domain.repository.AudioPlaybackController

val LocalAudioPlayerManager =
    staticCompositionLocalOf<AudioPlaybackController> {
        error("AudioPlaybackController not provided")
    }
