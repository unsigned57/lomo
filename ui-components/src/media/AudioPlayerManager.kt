package com.lomo.ui.media

import androidx.compose.runtime.staticCompositionLocalOf

val LocalAudioPlayerManager =
    staticCompositionLocalOf<AudioPlayerController> {
        error("AudioPlayerController not provided")
    }
