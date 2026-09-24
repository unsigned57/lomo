package com.lomo.domain.repository

import com.lomo.domain.model.StorageLocation

interface VoiceRecordingRepository {
    suspend fun start(outputLocation: StorageLocation)

    suspend fun stop()

    /**
     * One explicit amplitude sample. Null means the device could not produce a value (no active
     * recorder or a hardware read failure) — it is never collapsed into a fake zero.
     */
    fun sampleAmplitude(): Int?

    /** The capture device's mid-recording failure, if the hardware reported one. */
    fun captureFailure(): Throwable?
}
