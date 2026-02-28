package com.lomo.ui.media

import android.content.Context
import android.util.Log
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioPlayerManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private companion object {
            const val TAG = "AudioPlayerManager"
        }

        private var player: ExoPlayer? = null

        private val _currentPlayingUri = MutableStateFlow<String?>(null)
        val currentPlayingUri: StateFlow<String?> = _currentPlayingUri.asStateFlow()

        private val _isPlaying = MutableStateFlow(false)
        val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

        private val _playbackPosition = MutableStateFlow(0L)
        val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow()

        private val _duration = MutableStateFlow(0L)
        val duration: StateFlow<Long> = _duration.asStateFlow()

        private val coroutineExceptionHandler =
            CoroutineExceptionHandler { _, throwable ->
                logError("Audio player coroutine failed", throwable)
            }

        private var scopeJob = SupervisorJob()
        private var scope = createScope(scopeJob)

        private fun createScope(job: Job): CoroutineScope =
            CoroutineScope(Dispatchers.Main.immediate + job + coroutineExceptionHandler)

        private fun ensureActiveScope() {
            if (!scopeJob.isActive) {
                scopeJob = SupervisorJob()
                scope = createScope(scopeJob)
            }
        }

        private fun ensurePlayer() {
            if (player == null) {
                player =
                    ExoPlayer.Builder(context).build().apply {
                        addListener(
                            object : Player.Listener {
                                override fun onIsPlayingChanged(isPlaying: Boolean) {
                                    _isPlaying.value = isPlaying
                                }

                                override fun onPlaybackStateChanged(playbackState: Int) {
                                    if (playbackState == Player.STATE_ENDED) {
                                        _currentPlayingUri.value = null
                                        _isPlaying.value = false
                                        _playbackPosition.value = 0
                                    }
                                    updateDuration()
                                }
                            },
                        )
                    }
            }
        }

        private fun updateDuration() {
            player?.duration?.let { dur ->
                if (dur > 0) _duration.value = dur
            }
        }

        private var rootDirectory: String? = null
        private var voiceDirectory: String? = null

        fun setRootDirectory(path: String?) {
            this.rootDirectory = path
        }

        fun setVoiceDirectory(path: String?) {
            this.voiceDirectory = path
        }

        fun cancelScope() {
            scopeJob.cancel()
        }

        fun play(uri: String) {
            ensureActiveScope()
            scope.launch {
                ensurePlayer()
                val currentPlayer = player ?: return@launch

                val resolvedUri = resolveUri(uri)
                if (resolvedUri == null) {
                    return@launch
                }

                if (_currentPlayingUri.value == uri) {
                    if (currentPlayer.isPlaying) {
                        currentPlayer.pause()
                    } else {
                        currentPlayer.play()
                    }
                } else {
                    try {
                        currentPlayer.stop()
                        currentPlayer.setMediaItem(MediaItem.fromUri(resolvedUri))
                        currentPlayer.prepare()
                        currentPlayer.play()
                        _currentPlayingUri.value = uri
                    } catch (throwable: Throwable) {
                        if (throwable is CancellationException) throw throwable
                        logError("Failed to start playback for uri=$uri", throwable)
                    }
                }
            }
        }

        private suspend fun resolveUri(uri: String): String? {
            if (uri.startsWith("/") || uri.startsWith("content:") || uri.startsWith("http")) {
                return uri
            }

            val isJustFilename = !uri.contains("/")
            val baseDir = if (isJustFilename && voiceDirectory != null) voiceDirectory else rootDirectory

            if (baseDir == null) {
                logError("Cannot resolve relative uri because base directory is missing: $uri")
                return null
            }

            return withContext(Dispatchers.IO) {
                if (baseDir.startsWith("content://")) {
                    try {
                        val rootUri = android.net.Uri.parse(baseDir)
                        var docFile =
                            androidx.documentfile.provider.DocumentFile
                                .fromTreeUri(context, rootUri)

                        if (docFile == null || !docFile.isDirectory) {
                            return@withContext null
                        }

                        val parts = uri.split("/")
                        for (part in parts) {
                            val nextDoc = docFile?.findFile(part)
                            if (nextDoc == null) {
                                return@withContext null
                            }
                            docFile = nextDoc
                        }
                        docFile.uri.toString()
                    } catch (throwable: Throwable) {
                        if (throwable is CancellationException) throw throwable
                        logError("Failed to resolve SAF uri=$uri from baseDir=$baseDir", throwable)
                        null
                    }
                } else {
                    val file = java.io.File(baseDir, uri)
                    if (file.exists()) file.absolutePath else null
                }
            }
        }

        fun seekTo(positionMs: Long) {
            player?.seekTo(positionMs)
        }

        fun pause() {
            player?.pause()
        }

        fun stop() {
            player?.stop()
            _currentPlayingUri.value = null
            _isPlaying.value = false
        }

        fun release() {
            cancelScope()
            player?.release()
            player = null
            _currentPlayingUri.value = null
            _isPlaying.value = false
            _playbackPosition.value = 0
            _duration.value = 0
        }

        fun updateProgress() {
            player?.let {
                if (it.isPlaying) {
                    _playbackPosition.value = it.currentPosition
                    updateDuration()
                }
            }
        }

        private fun logError(
            message: String,
            throwable: Throwable? = null,
        ) {
            if (throwable == null) {
                Log.e(TAG, message)
            } else {
                Log.e(TAG, message, throwable)
            }
        }
    }

val LocalAudioPlayerManager =
    staticCompositionLocalOf<AudioPlayerManager> {
        error("AudioPlayerManager not provided")
    }
