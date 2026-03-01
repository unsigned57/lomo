package com.lomo.data.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.VoiceRecordingRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
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
            val targetUri = android.net.Uri.parse(outputLocation.raw)

            val mediaRecorder = createRecorder()
            try {
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
            } catch (e: Exception) {
                Log.e("AudioRecorder", "Failed to start recording", e)
                try {
                    mediaRecorder.release()
                } catch (releaseError: Exception) {
                    Log.e("AudioRecorder", "Failed to release recorder after start failure", releaseError)
                }
                closeOutputFileDescriptor()
            }
        }

        override fun stop() {
            if (!isRecording) return

            try {
                recorder?.stop()
            } catch (e: Exception) {
                Log.e("AudioRecorder", "Failed to stop recording", e)
            } finally {
                release()
            }
        }

        override fun getAmplitude(): Int =
            try {
                recorder?.maxAmplitude ?: 0
            } catch (e: Exception) {
                0
            }

        // Convert amplitude to decibels for visualization if needed
        fun getDecibels(): Float {
            val amplitude = getAmplitude()
            return if (amplitude > 0) 20 * log10(amplitude.toDouble()).toFloat() else -80f
        }

        private fun createRecorder(): MediaRecorder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

        private fun release() {
            try {
                recorder?.release()
            } catch (e: Exception) {
                Log.e("AudioRecorder", "Failed to release recorder", e)
            }
            recorder = null
            closeOutputFileDescriptor()
            isRecording = false
        }

        private fun closeOutputFileDescriptor() {
            try {
                outputFileDescriptor?.close()
            } catch (e: Exception) {
                Log.e("AudioRecorder", "Failed to close output file descriptor", e)
            }
            outputFileDescriptor = null
        }
    }
