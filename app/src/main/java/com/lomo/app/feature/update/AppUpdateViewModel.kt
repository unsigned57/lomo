package com.lomo.app.feature.update

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
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
    val expectedPackageName: String? = null,
    val expectedVersionName: String? = null,
    val expectedVersionCode: Long? = null,
    val debugSimulationScenario: DebugAppUpdateScenario? = null,
)

@HiltViewModel
class AppUpdateViewModel
    @Inject
    constructor(
        private val updateStartupOrchestrator: UpdateStartupOrchestrator,
        private val appUpdateDownloadManager: AppUpdateDownloadManager,
    ) : ViewModel() {
        val dialogState: StateFlow<AppUpdateDialogState?> = updateStartupOrchestrator.dialogState
        val progressDialogState: StateFlow<AppUpdateProgressDialogState?> = appUpdateDownloadManager.progressDialogState

        fun startInAppUpdate(update: AppUpdateDialogState? = null) {
            val resolvedUpdate = update ?: updateStartupOrchestrator.consumeUpdateDialog() ?: return
            if (update != null) {
                updateStartupOrchestrator.dismissUpdateDialog()
            }
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
            updateStartupOrchestrator.dismissUpdateDialog()
        }
    }
