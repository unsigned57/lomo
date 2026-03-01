package com.lomo.domain.repository

import com.lomo.domain.model.StorageLocation

interface VoiceRecordingRepository {
    fun start(outputLocation: StorageLocation)

    fun stop()

    fun getAmplitude(): Int
}
