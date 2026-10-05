package com.lomo.data.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.VoiceRecordingRepository
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider

import kotlinx.coroutines.withContext
import timber.log.Timber

import kotlin.coroutines.cancellation.CancellationException

class AudioRecorder(
    private val context: Context,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) : VoiceRecordingRepository {
        // behavior-contract: stateful-var-ok: this is process-owned resource/lease/job handle, not a query cache
        private var recorder: MediaRecorder? = null
        // behavior-contract: stateful-var-ok: this is process-owned resource/lease/job handle, not a query cache
        private var outputFileDescriptor: ParcelFileDescriptor? = null
        // behavior-contract: stateful-var-ok: this is process-owned resource/lease/job handle, not a query cache
        private var isRecording = false
        private val pendingCaptureFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()

        override suspend fun start(outputLocation: StorageLocation) {
            withContext(dispatcherProvider.io) {
                if (isRecording) {
                    stop()
                }
                val mediaRecorder = createRecorder()
                try {
                    pendingCaptureFailure.set(null)
                    mediaRecorder.setOnErrorListener { _, what, extra ->
                        pendingCaptureFailure.set(
                            IllegalStateException("MediaRecorder capture failed: what=$what extra=$extra"),
                        )
                    }
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
                } catch (error: Exception) {
                    val message =
                        if (error is CancellationException) {
                            "Failed to release recorder after start cancellation"
                        } else {
                            "Failed to release recorder after start failure"
                        }
                    if (error !is CancellationException) {
                        Timber.tag(TAG).e(error, "Failed to start recording")
                    }
                    releaseRecorder(mediaRecorder, message)
                    closeOutputFileDescriptor()
                    throw error
                }
            }
        }

        override suspend fun stop() {
            withContext(dispatcherProvider.io) {
                if (!isRecording) return@withContext
                val activeRecorder = requireActiveRecorder()
                try {
                    activeRecorder.stop()
                } catch (error: Exception) {
                    if (error !is CancellationException) {
                        Timber.tag(TAG).e(error, "Failed to stop recording")
                    }
                    release()
                    throw error
                }
                release()
            }
        }

        override fun sampleAmplitude(): Int? {
            val activeRecorder = recorder ?: return null
            return try {
                activeRecorder.maxAmplitude
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // behavior-contract: silent-result-ok: amplitude absence is the documented Int?
                // contract — a failed sample means "no amplitude this tick", never a fake zero
                Timber.tag(TAG).w(error, "Failed to read recording amplitude")
                null
            }
        }

        override fun captureFailure(): Throwable? = pendingCaptureFailure.get()

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

private const val TAG = "AudioRecorder"
