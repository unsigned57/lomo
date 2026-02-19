package com.lomo.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.data.source.FileDataSource
import com.lomo.data.util.FormatDetector
import com.lomo.data.util.PreferenceKeys
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val repository: MemoRepository,
        private val settings: SettingsRepository,
        private val formatDetector: FormatDetector,
        private val fileDataSource: FileDataSource,
    ) : ViewModel() {
        val rootDirectory: StateFlow<String> =
            repository
                .getRootDisplayName()
                .map { it ?: "" }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

        val imageDirectory: StateFlow<String> =
            repository
                .getImageDisplayName()
                .map { it ?: "" }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

        val voiceDirectory: StateFlow<String> =
            repository
                .getVoiceDisplayName()
                .map { it ?: "" }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

        val dateFormat: StateFlow<String> =
            settings
                .getDateFormat()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.DATE_FORMAT,
                )

        val timeFormat: StateFlow<String> =
            settings
                .getTimeFormat()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.TIME_FORMAT,
                )

        val themeMode: StateFlow<String> =
            settings
                .getThemeMode()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.THEME_MODE,
                )

        val hapticFeedbackEnabled: StateFlow<Boolean> =
            settings
                .isHapticFeedbackEnabled()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.HAPTIC_FEEDBACK_ENABLED,
                )

        val showInputHints: StateFlow<Boolean> =
            settings
                .isShowInputHintsEnabled()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.SHOW_INPUT_HINTS,
                )

        val storageFilenameFormat: StateFlow<String> =
            settings
                .getStorageFilenameFormat()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.STORAGE_FILENAME_FORMAT,
                )

        val storageTimestampFormat: StateFlow<String> =
            settings
                .getStorageTimestampFormat()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.STORAGE_TIMESTAMP_FORMAT,
                )

        val checkUpdatesOnStartup: StateFlow<Boolean> =
            settings
                .isCheckUpdatesOnStartupEnabled()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.CHECK_UPDATES_ON_STARTUP,
                )

        fun updateRootDirectory(path: String) {
            viewModelScope.launch { settings.setRootDirectory(path) }
        }

        fun updateRootUri(uriString: String) {
            viewModelScope.launch { settings.updateRootUri(uriString) }
        }

        fun updateImageDirectory(path: String) {
            viewModelScope.launch { settings.setImageDirectory(path) }
        }

        fun updateImageUri(uriString: String) {
            viewModelScope.launch { settings.updateImageUri(uriString) }
        }

        fun updateVoiceDirectory(path: String) {
            viewModelScope.launch { settings.setVoiceDirectory(path) }
        }

        fun updateVoiceUri(uriString: String) {
            viewModelScope.launch { settings.updateVoiceUri(uriString) }
        }

        fun updateDateFormat(format: String) {
            viewModelScope.launch { settings.setDateFormat(format) }
        }

        fun updateTimeFormat(format: String) {
            viewModelScope.launch { settings.setTimeFormat(format) }
        }

        fun updateThemeMode(mode: String) {
            viewModelScope.launch { settings.setThemeMode(mode) }
        }

        fun updateStorageFilenameFormat(format: String) {
            viewModelScope.launch { settings.setStorageFilenameFormat(format) }
        }

        fun updateStorageTimestampFormat(format: String) {
            viewModelScope.launch { settings.setStorageTimestampFormat(format) }
        }

        fun updateHapticFeedback(enabled: Boolean) {
            viewModelScope.launch { settings.setHapticFeedbackEnabled(enabled) }
        }

        fun updateShowInputHints(enabled: Boolean) {
            viewModelScope.launch { settings.setShowInputHints(enabled) }
        }

        fun updateCheckUpdatesOnStartup(enabled: Boolean) {
            viewModelScope.launch { settings.setCheckUpdatesOnStartup(enabled) }
        }

        fun autoDetectFormats() {
            viewModelScope.launch {
                try {
                    // 仅采样最近 20 个文件的首行，避免大目录全量读
                    val metas = fileDataSource.listMetadata().sortedByDescending { it.lastModified }.take(20)
                    if (metas.isEmpty()) return@launch
                    val filenames = metas.map { it.filename }
                    val heads = metas.map { meta ->
                        // 轻量读取前 256 字符
                        fileDataSource.readHead(meta.filename, 256)?.lineSequence()?.firstOrNull() ?: ""
                    }
                    val (detectedFilename, detectedTimestamp) = formatDetector.detectFormats(filenames, heads)
                    if (detectedFilename != null) settings.setStorageFilenameFormat(detectedFilename)
                    if (detectedTimestamp != null) settings.setStorageTimestampFormat(detectedTimestamp)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("SettingsViewModel", "Auto-detect failed", e)
                }
            }
        }
    }
