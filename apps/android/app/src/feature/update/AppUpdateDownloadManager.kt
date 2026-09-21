package com.lomo.app.feature.update

import android.content.Context
import com.lomo.domain.model.AppUpdateInstallState
import com.lomo.domain.usecase.CancelAppUpdateDownloadUseCase
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import com.lomo.domain.usecase.DownloadAndInstallAppUpdateUseCase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow


data class AppUpdateProgressDialogState(
    val update: AppUpdateDialogState,
    val installState: AppUpdateInstallState,
)

class AppUpdateDownloadManager(
    context: Context,
    downloadAndInstallAppUpdateUseCase: DownloadAndInstallAppUpdateUseCase,
    cancelAppUpdateDownloadUseCase: CancelAppUpdateDownloadUseCase,
    dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) {
        // behavior-contract: unmanaged-scope-ok: process-lifetime in-app update download session
        private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.main)
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
