package com.lomo.app.feature.main

import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.VoiceRecordingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

open class RecordingCoordinator
    @Inject
    constructor(
        private val directorySettingsRepository: DirectorySettingsRepository,
        private val mediaRepository: MediaRepository,
        private val voiceRecordingRepository: VoiceRecordingRepository,
    ) {
        open fun voiceDirectory(): Flow<String?> =
            directorySettingsRepository
                .observeLocation(StorageArea.VOICE)
                .map { it?.raw }

        open suspend fun startRecording(filename: String): String =
            withContext(Dispatchers.IO) {
                val target = mediaRepository.allocateVoiceCaptureTarget(MediaEntryId(filename)).raw
                voiceRecordingRepository.start(StorageLocation(target))
                target
            }

        open suspend fun stopRecording() {
            withContext(Dispatchers.IO) {
                voiceRecordingRepository.stop()
            }
        }

        open fun currentAmplitude(): Int = voiceRecordingRepository.getAmplitude()

        open suspend fun discardRecording(filename: String?) {
            withContext(Dispatchers.IO) {
                runCatching { voiceRecordingRepository.stop() }
                if (!filename.isNullOrBlank()) {
                    mediaRepository.removeVoiceCapture(MediaEntryId(filename))
                }
            }
        }

        open fun stopSilently() {
            runCatching { voiceRecordingRepository.stop() }
        }
    }
