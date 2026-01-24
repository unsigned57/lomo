package com.lomo.data.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.lomo.domain.repository.VoiceRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlin.math.log10

class AudioRecorder
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : VoiceRecorder {
        private var recorder: MediaRecorder? = null
        private var isRecording = false

        override fun start(outputUri: android.net.Uri) {
            if (isRecording) {
                stop()
            }

            createRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

                try {
                    val pfd =
                        context.contentResolver.openFileDescriptor(outputUri, "w")
                            ?: throw java.io.IOException("Cannot open file descriptor for $outputUri")
                    setOutputFile(pfd.fileDescriptor)

                    prepare()
                    start()
                    recorder = this
                    isRecording = true
                } catch (e: Exception) {
                    Log.e("AudioRecorder", "Failed to start recording", e)
                    release()
                }
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
            recorder?.release()
            recorder = null
            isRecording = false
        }
    }
