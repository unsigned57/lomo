package com.lomo.data.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.VoiceRecordingRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.log10

class AudioRecorder
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : VoiceRecordingRepository {
        private var recorder: MediaRecorder? = null
        private var outputFileDescriptor: ParcelFileDescriptor? = null
        private var isRecording = false

        override fun start(outputLocation: StorageLocation) {
            if (isRecording) {
                stop()
            }
            val targetUri = outputLocation.raw.toUri()

            val mediaRecorder = createRecorder()
            runCatching {
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

                val openedDescriptor =
                    context.contentResolver.openFileDescriptor(targetUri, "w")
                        ?: throw java.io.IOException("Cannot open file descriptor for $targetUri")
                outputFileDescriptor = openedDescriptor
                mediaRecorder.setOutputFile(openedDescriptor.fileDescriptor)

                mediaRecorder.prepare()
                mediaRecorder.start()
                recorder = mediaRecorder
                isRecording = true
            }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    Timber.tag(TAG).e(error, "Failed to start recording")
                    releaseRecorder(mediaRecorder, "Failed to release recorder after start failure")
                    closeOutputFileDescriptor()
                }
        }

        override fun stop() {
            if (!isRecording) return

            runCatching {
                recorder?.stop()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Timber.tag(TAG).e(error, "Failed to stop recording")
            }.also {
                release()
            }
        }

        override fun getAmplitude(): Int =
            runCatching {
                recorder?.maxAmplitude ?: 0
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                Timber.tag(TAG).w(error, "Failed to read recording amplitude")
                0
            }

        // Convert amplitude to decibels for visualization if needed
        fun getDecibels(): Float {
            val amplitude = getAmplitude()
            return if (amplitude > 0) {
                DECIBELS_MULTIPLIER * log10(amplitude.toDouble()).toFloat()
            } else {
                MIN_DECIBELS
            }
        }

        private fun createRecorder(): MediaRecorder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder::class.java.getDeclaredConstructor().newInstance()
            }

        private fun release() {
            runCatching {
                recorder?.release()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Timber.tag(TAG).e(error, "Failed to release recorder")
            }
            recorder = null
            closeOutputFileDescriptor()
            isRecording = false
        }

        private fun closeOutputFileDescriptor() {
            runCatching {
                outputFileDescriptor?.close()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Timber.tag(TAG).e(error, "Failed to close output file descriptor")
            }
            outputFileDescriptor = null
        }

        private fun releaseRecorder(
            mediaRecorder: MediaRecorder,
            message: String,
        ) {
            runCatching {
                mediaRecorder.release()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Timber.tag(TAG).e(error, message)
            }
        }
    }

private const val DECIBELS_MULTIPLIER = 20
private const val MIN_DECIBELS = -80f
private const val TAG = "AudioRecorder"
