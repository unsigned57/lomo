package com.lomo.ui.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioPlayerManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private var player: ExoPlayer? = null

        private val _currentPlayingUri = MutableStateFlow<String?>(null)
        val currentPlayingUri: StateFlow<String?> = _currentPlayingUri.asStateFlow()

        private val _isPlaying = MutableStateFlow(false)
        val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

        private val _playbackPosition = MutableStateFlow(0L)
        val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow()

        private val _duration = MutableStateFlow(0L)
        val duration: StateFlow<Long> = _duration.asStateFlow()

        init {
            // Initialize player lazily or on demand? For now on init to keep it simple but maybe better on demand
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

        private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())

        fun play(uri: String) {
            scope.launch {
                ensurePlayer()
                val currentPlayer = player ?: return@launch

                // Resolve absolute path if relative
                val resolvedUri = resolveUri(uri)
                if (resolvedUri == null) {
                    return@launch
                }

                if (_currentPlayingUri.value == uri) { // Track original URI for UI comparison
                    // Toggle play/pause
                    if (currentPlayer.isPlaying) {
                        currentPlayer.pause()
                    } else {
                        currentPlayer.play()
                    }
                } else {
                    // New audio
                    try {
                        currentPlayer.stop()
                        currentPlayer.setMediaItem(MediaItem.fromUri(resolvedUri))
                        currentPlayer.prepare()
                        currentPlayer.play()
                        _currentPlayingUri.value = uri // Store original URI to match UI
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        private suspend fun resolveUri(uri: String): String? {
            if (uri.startsWith("/") || uri.startsWith("content:") || uri.startsWith("http")) {
                return uri
            }

            // Determine which directory to use for resolution
            // If uri contains '/' (e.g., "attachments/file.m4a"), use rootDirectory
            // If uri is just a filename (e.g., "file.m4a"), use voiceDirectory (custom voice dir)
            val isJustFilename = !uri.contains("/")
            val baseDir = if (isJustFilename && voiceDirectory != null) voiceDirectory else rootDirectory

            if (baseDir == null) {
                return null
            }

            return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (baseDir.startsWith("content://")) {
                    // Handle SAF
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
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                } else {
                    // Handle File
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
            player?.release()
            player = null
        }

        // Call this periodically from UI e.g. LaunchedEffect to update progress
        fun updateProgress() {
            player?.let {
                if (it.isPlaying) {
                    _playbackPosition.value = it.currentPosition
                    updateDuration()
                }
            }
        }
    }

val LocalAudioPlayerManager =
    androidx.compose.runtime.staticCompositionLocalOf<AudioPlayerManager> {
        error("AudioPlayerManager not provided")
    }
