package com.lomo.domain.repository

import android.net.Uri

interface VoiceRecorder {
    fun start(outputUri: Uri)

    fun stop()

    fun getAmplitude(): Int
    // release() is lifecycle managed effectively by implementation, usually not exposed unless needed
}
