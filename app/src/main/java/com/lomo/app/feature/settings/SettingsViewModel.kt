package com.lomo.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.data.source.FileDataSource
import com.lomo.data.util.FormatDetector
import com.lomo.data.util.PreferenceKeys
import com.lomo.domain.repository.MemoRepository
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
    private val formatDetector: FormatDetector,
    private val fileDataSource: FileDataSource
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
        repository
            .getDateFormat()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                PreferenceKeys.Defaults.DATE_FORMAT
            )

    val timeFormat: StateFlow<String> =
        repository
            .getTimeFormat()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                PreferenceKeys.Defaults.TIME_FORMAT
            )

    val themeMode: StateFlow<String> =
        repository
            .getThemeMode()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                PreferenceKeys.Defaults.THEME_MODE
            )

    val hapticFeedbackEnabled: StateFlow<Boolean> =
        repository
            .isHapticFeedbackEnabled()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                PreferenceKeys.Defaults.HAPTIC_FEEDBACK_ENABLED
            )

    val storageFilenameFormat: StateFlow<String> =
        repository
            .getStorageFilenameFormat()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                PreferenceKeys.Defaults.STORAGE_FILENAME_FORMAT
            )

    val storageTimestampFormat: StateFlow<String> =
        repository
            .getStorageTimestampFormat()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                PreferenceKeys.Defaults.STORAGE_TIMESTAMP_FORMAT
            )

    val checkUpdatesOnStartup: StateFlow<Boolean> =
        repository
            .isCheckUpdatesOnStartupEnabled()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                PreferenceKeys.Defaults.CHECK_UPDATES_ON_STARTUP
            )

    fun updateRootDirectory(path: String) {
        viewModelScope.launch { repository.setRootDirectory(path) }
    }

    fun updateRootUri(uriString: String) {
        viewModelScope.launch { repository.updateRootUri(uriString) }
    }

    fun updateImageDirectory(path: String) {
        viewModelScope.launch { repository.setImageDirectory(path) }
    }

    fun updateImageUri(uriString: String) {
        viewModelScope.launch { repository.updateImageUri(uriString) }
    }

    fun updateVoiceDirectory(path: String) {
        viewModelScope.launch { repository.setVoiceDirectory(path) }
    }

    fun updateVoiceUri(uriString: String) {
        viewModelScope.launch { repository.updateVoiceUri(uriString) }
    }

    fun updateDateFormat(format: String) {
        viewModelScope.launch { repository.setDateFormat(format) }
    }

    fun updateTimeFormat(format: String) {
        viewModelScope.launch { repository.setTimeFormat(format) }
    }

    fun updateThemeMode(mode: String) {
        viewModelScope.launch { repository.setThemeMode(mode) }
    }

    fun updateStorageFilenameFormat(format: String) {
        viewModelScope.launch { repository.setStorageFilenameFormat(format) }
    }

    fun updateStorageTimestampFormat(format: String) {
        viewModelScope.launch { repository.setStorageTimestampFormat(format) }
    }

    fun updateHapticFeedback(enabled: Boolean) {
        viewModelScope.launch { repository.setHapticFeedbackEnabled(enabled) }
    }

    fun updateCheckUpdatesOnStartup(enabled: Boolean) {
        viewModelScope.launch { repository.setCheckUpdatesOnStartup(enabled) }
    }

    fun autoDetectFormats() {
        viewModelScope.launch {
            try {
                val files = fileDataSource.listFiles()
                if (files.isEmpty()) return@launch

                val filenames = files.map { it.filename }
                val contents = files.map { it.content.lines().firstOrNull() ?: "" }

                val (detectedFilename, detectedTimestamp) =
                    formatDetector.detectFormats(filenames, contents)

                if (detectedFilename != null) {
                    repository.setStorageFilenameFormat(detectedFilename)
                }
                if (detectedTimestamp != null) {
                    repository.setStorageTimestampFormat(detectedTimestamp)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Log error
                android.util.Log.e("SettingsViewModel", "Auto-detect failed", e)
            }
        }
    }
}
