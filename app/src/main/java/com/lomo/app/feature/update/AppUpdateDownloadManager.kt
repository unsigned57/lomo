package com.lomo.app.feature.update

import android.content.Context
import com.lomo.app.R
import com.lomo.domain.model.AppUpdateInfo
import com.lomo.domain.model.AppUpdateInstallState
import com.lomo.domain.usecase.CancelAppUpdateDownloadUseCase
import com.lomo.domain.usecase.DownloadAndInstallAppUpdateUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import javax.inject.Inject
import javax.inject.Singleton

private const val DEBUG_UPDATE_STEP_DELAY_MS = 280L
private const val DEBUG_UPDATE_TERMINAL_DELAY_MS = 220L

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
        private val _progressDialogState = MutableStateFlow<AppUpdateProgressDialogState?>(null)
        val progressDialogState: StateFlow<AppUpdateProgressDialogState?> = _progressDialogState.asStateFlow()

        private var activeDownloadJob: kotlinx.coroutines.Job? = null
        private var currentUpdate: AppUpdateDialogState? = null

        fun startInAppUpdate(update: AppUpdateDialogState) {
            if (activeDownloadJob?.isActive == true) {
                return
            }
            currentUpdate = update
            activeDownloadJob =
                scope.launch {
                    try {
                        // Wait one main-loop turn so any existing dialog window can dismiss cleanly
                        // before the progress dialog is attached.
                        yield()
                        publishState(
                            update = update,
                            installState = AppUpdateInstallState.Preparing,
                        )
                        val debugScenario = update.debugSimulationScenario
                        if (debugScenario != null) {
                            runDebugSimulation(update, debugScenario)
                        } else {
                            downloadAndInstallAppUpdateUseCase(update.toAppUpdateInfo()).collect { state ->
                                publishState(update = update, installState = state)
                            }
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        publishState(
                            update = update,
                            installState =
                                AppUpdateInstallState.Failed(
                                    message =
                                        error.message
                                            ?.takeIf { it.isNotBlank() }
                                            ?: context.getString(R.string.update_download_failed_generic),
                                ),
                        )
                    } finally {
                        activeDownloadJob = null
                    }
                }
        }

        fun retryInAppUpdate() {
            val update = currentUpdate ?: return
            startInAppUpdate(update)
        }

        fun cancelInAppUpdate() {
            cancelAppUpdateDownloadUseCase()
            activeDownloadJob?.cancel()
            activeDownloadJob = null
            _progressDialogState.value = null
        }

        fun dismissProgressDialog() {
            if (activeDownloadJob?.isActive == true) {
                return
            }
            _progressDialogState.value = null
        }

        private suspend fun runDebugSimulation(
            update: AppUpdateDialogState,
            scenario: DebugAppUpdateScenario,
        ) {
            publishState(update = update, installState = AppUpdateInstallState.Preparing)
            delay(DEBUG_UPDATE_STEP_DELAY_MS)
            publishState(update = update, installState = AppUpdateInstallState.Downloading(progress = 19))
            delay(DEBUG_UPDATE_STEP_DELAY_MS)
            publishState(update = update, installState = AppUpdateInstallState.Downloading(progress = 52))
            delay(DEBUG_UPDATE_STEP_DELAY_MS)
            publishState(update = update, installState = AppUpdateInstallState.Downloading(progress = 87))
            delay(DEBUG_UPDATE_STEP_DELAY_MS)
            when (scenario) {
                DebugAppUpdateScenario.Success -> {
                    publishState(update = update, installState = AppUpdateInstallState.Installing)
                    delay(DEBUG_UPDATE_TERMINAL_DELAY_MS)
                    publishState(update = update, installState = AppUpdateInstallState.Completed)
                }

                DebugAppUpdateScenario.Failure ->
                    publishState(
                        update = update,
                        installState =
                            AppUpdateInstallState.Failed(
                                message = context.getString(R.string.debug_update_simulation_failed),
                            ),
                    )
            }
        }

        private fun publishState(
            update: AppUpdateDialogState,
            installState: AppUpdateInstallState,
        ) {
            _progressDialogState.value =
                AppUpdateProgressDialogState(
                    update = update,
                    installState = installState,
                )
        }
    }

private fun AppUpdateDialogState.toAppUpdateInfo(): AppUpdateInfo =
    AppUpdateInfo(
        url = url,
        version = version,
        releaseNotes = releaseNotes,
        apkDownloadUrl = apkDownloadUrl,
        apkFileName = apkFileName,
        apkSizeBytes = apkSizeBytes,
    )
