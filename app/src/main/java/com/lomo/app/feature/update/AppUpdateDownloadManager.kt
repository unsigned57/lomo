package com.lomo.app.feature.update

import android.content.Context
import com.lomo.domain.model.AppUpdateInstallState
import com.lomo.domain.usecase.CancelAppUpdateDownloadUseCase
import com.lomo.domain.usecase.DownloadAndInstallAppUpdateUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class AppUpdateProgressDialogState(
    val update: AppUpdateDialogState,
    val installState: AppUpdateInstallState,
)

@Singleton
class AppUpdateDownloadManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val downloadAndInstallAppUpdateUseCase: DownloadAndInstallAppUpdateUseCase,
        private val cancelAppUpdateDownloadUseCase: CancelAppUpdateDownloadUseCase,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val downloadSession =
            AppUpdateDownloadSession(
                context = context,
                downloadAndInstallAppUpdateUseCase = downloadAndInstallAppUpdateUseCase,
                cancelAppUpdateDownloadUseCase = cancelAppUpdateDownloadUseCase,
                scope = scope,
            )
        val progressDialogState: StateFlow<AppUpdateProgressDialogState?> = downloadSession.progressDialogState

        fun startInAppUpdate(update: AppUpdateDialogState) {
            downloadSession.start(update)
        }

        fun retryInAppUpdate() {
            downloadSession.retry()
        }

        fun cancelInAppUpdate() {
            downloadSession.cancel()
        }

        fun dismissProgressDialog() {
            downloadSession.dismissProgressDialog()
        }
    }
