package com.lomo.data.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.VoiceRecordingRepository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.log10

class AudioRecorder(
    private val context: Context,
) : VoiceRecordingRepository {
        private var recorder: MediaRecorder? = null
        private var outputFileDescriptor: ParcelFileDescriptor? = null
        private var isRecording = false

        override suspend fun start(outputLocation: StorageLocation) {
            withContext(Dispatchers.IO) {
                if (isRecording) {
                    stop()
                }
                val mediaRecorder = createRecorder()
                try {
                    mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                    mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

                    val openedDescriptor = openOutputFileDescriptor(outputLocation)
                    outputFileDescriptor = openedDescriptor
                    mediaRecorder.setOutputFile(openedDescriptor.fileDescriptor)

                    mediaRecorder.prepare()
                    mediaRecorder.start()
                    recorder = mediaRecorder
                    isRecording = true
                } catch (cancellation: CancellationException) {
                    releaseRecorder(mediaRecorder, "Failed to release recorder after start cancellation")
                    closeOutputFileDescriptor()
                    throw cancellation
                } catch (error: Exception) {
                    Timber.tag(TAG).e(error, "Failed to start recording")
                    releaseRecorder(mediaRecorder, "Failed to release recorder after start failure")
                    closeOutputFileDescriptor()
                    throw error
                }
            }
        }

        override suspend fun stop() {
            withContext(Dispatchers.IO) {
                if (!isRecording) return@withContext
                val activeRecorder = requireActiveRecorder()
                try {
                    activeRecorder.stop()
                } catch (cancellation: CancellationException) {
                    release()
                    throw cancellation
                } catch (error: Exception) {
                    Timber.tag(TAG).e(error, "Failed to stop recording")
                    release()
                    throw error
                }
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

        private fun openOutputFileDescriptor(outputLocation: StorageLocation): ParcelFileDescriptor {
            val targetUri = outputLocation.raw.toUri()
            return context.contentResolver.openFileDescriptor(targetUri, "w")
                ?: throw java.io.IOException("Cannot open file descriptor for $targetUri")
        }

        private fun requireActiveRecorder(): MediaRecorder =
            recorder
                ?: error(
                    "Recording state requires an active MediaRecorder.",
                )

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
