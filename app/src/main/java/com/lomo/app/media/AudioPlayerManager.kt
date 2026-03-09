package com.lomo.app.media

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.AudioPlaybackController
import com.lomo.domain.repository.AudioPlaybackResolverRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioPlayerManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val uriResolver: AudioPlaybackResolverRepository,
    ) : AudioPlaybackController {
        private companion object {
            const val TAG = "AudioPlayerManager"
            const val PROGRESS_UPDATE_INTERVAL_MS = 100L
        }

        private var player: ExoPlayer? = null

        private val _currentPlayingUri = MutableStateFlow<String?>(null)
        override val currentPlayingUri: StateFlow<String?> = _currentPlayingUri.asStateFlow()

        private val _isPlaying = MutableStateFlow(false)
        override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

        private val _playbackPosition = MutableStateFlow(0L)
        override val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow()

        private val _duration = MutableStateFlow(0L)
        override val duration: StateFlow<Long> = _duration.asStateFlow()

        private val coroutineExceptionHandler =
            CoroutineExceptionHandler { _, throwable ->
                logError("Audio player coroutine failed", throwable)
            }

        private var scopeJob = SupervisorJob()
        private var scope = createScope(scopeJob)
        private var progressUpdateJob: Job? = null

        private fun createScope(job: Job): CoroutineScope = CoroutineScope(Dispatchers.Main.immediate + job + coroutineExceptionHandler)

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
                                    if (isPlaying) {
                                        startProgressUpdates()
                                    } else {
                                        updateProgress()
                                        stopProgressUpdates()
                                    }
                                }

                                override fun onPlaybackStateChanged(playbackState: Int) {
                                    if (playbackState == Player.STATE_ENDED) {
                                        stopProgressUpdates()
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

        override fun setRootLocation(location: StorageLocation?) {
            uriResolver.setRootLocation(location)
        }

        override fun setVoiceLocation(location: StorageLocation?) {
            uriResolver.setVoiceLocation(location)
        }

        fun cancelScope() {
            stopProgressUpdates()
            scopeJob.cancel()
        }

        private fun startProgressUpdates() {
            if (progressUpdateJob?.isActive == true) {
                return
            }

            ensureActiveScope()
            progressUpdateJob =
                scope.launch {
                    while (isActive) {
                        val currentPlayer = player
                        if (currentPlayer == null || !currentPlayer.isPlaying) {
                            break
                        }
                        updateProgress()
                        delay(PROGRESS_UPDATE_INTERVAL_MS)
                    }
                }
        }

        private fun stopProgressUpdates() {
            progressUpdateJob?.cancel()
            progressUpdateJob = null
        }

        override fun play(uri: String) {
            ensureActiveScope()
            scope.launch {
                ensurePlayer()
                val currentPlayer = player ?: return@launch

                val resolvedUri = uriResolver.resolve(uri)
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
                        stopProgressUpdates()
                        currentPlayer.stop()
                        _playbackPosition.value = 0
                        _duration.value = 0
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

        override fun seekTo(positionMs: Long) {
            player?.seekTo(positionMs)
            updateProgress()
        }

        override fun pause() {
            player?.pause()
        }

        override fun stop() {
            stopProgressUpdates()
            player?.stop()
            _currentPlayingUri.value = null
            _isPlaying.value = false
        }

        override fun release() {
            cancelScope()
            player?.release()
            player = null
            _currentPlayingUri.value = null
            _isPlaying.value = false
            _playbackPosition.value = 0
            _duration.value = 0
        }

        override fun updateProgress() {
            player?.let {
                _playbackPosition.value = it.currentPosition
                updateDuration()
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
