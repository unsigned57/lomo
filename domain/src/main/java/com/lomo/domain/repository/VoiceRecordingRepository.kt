package com.lomo.domain.repository

import com.lomo.domain.model.StorageLocation

interface VoiceRecordingRepository {
    suspend fun start(outputLocation: StorageLocation)

    suspend fun stop()

    fun getAmplitude(): Int
}
