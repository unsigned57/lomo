package com.lomo.domain.device

interface VoiceRecorder {
    fun start(outputUri: String)

    fun stop()

    fun getAmplitude(): Int
}
