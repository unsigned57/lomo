package com.lomo.app.feature.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class DebugAppUpdateScenario {
    Success,
    Failure,
}

data class AppUpdateDialogState(
    val url: String,
    val version: String,
    val releaseNotes: String,
    val apkDownloadUrl: String? = null,
    val apkFileName: String? = null,
    val apkSizeBytes: Long? = null,
    val debugSimulationScenario: DebugAppUpdateScenario? = null,
)

@HiltViewModel
class AppUpdateViewModel
    @Inject
    constructor(
        private val appUpdateChecker: AppUpdateChecker,
        private val appUpdateDownloadManager: AppUpdateDownloadManager,
    ) : ViewModel() {
        private val _dialogState = MutableStateFlow<AppUpdateDialogState?>(null)
        val dialogState: StateFlow<AppUpdateDialogState?> = _dialogState.asStateFlow()
        val progressDialogState: StateFlow<AppUpdateProgressDialogState?> = appUpdateDownloadManager.progressDialogState

        init {
            checkForUpdates()
        }

        fun checkForUpdates() {
            viewModelScope.launch {
                try {
                    val info = appUpdateChecker.checkForStartupUpdate() ?: return@launch
                    _dialogState.value =
                        AppUpdateDialogState(
                            url = info.url,
                            version = info.version,
                            releaseNotes = info.releaseNotes,
                            apkDownloadUrl = info.apkDownloadUrl,
                            apkFileName = info.apkFileName,
                            apkSizeBytes = info.apkSizeBytes,
                        )
                } catch (_: Exception) {
                    // Ignore update check errors.
                }
            }
        }

        fun startInAppUpdate(update: AppUpdateDialogState? = _dialogState.value) {
            val resolvedUpdate = update ?: return
            _dialogState.value = null
            appUpdateDownloadManager.startInAppUpdate(resolvedUpdate)
        }

        fun retryInAppUpdate() {
            appUpdateDownloadManager.retryInAppUpdate()
        }

        fun cancelInAppUpdate() {
            appUpdateDownloadManager.cancelInAppUpdate()
        }

        fun dismissProgressDialog() {
            appUpdateDownloadManager.dismissProgressDialog()
        }

        fun dismissUpdateDialog() {
            _dialogState.value = null
        }
    }
