package com.lomo.app.feature.update

import android.content.Context
import com.lomo.app.R
import com.lomo.app.util.runSuspendCatching
import com.lomo.domain.model.AppUpdateInfo
import com.lomo.domain.model.AppUpdateInstallState
import com.lomo.domain.usecase.CancelAppUpdateDownloadUseCase
import com.lomo.domain.usecase.DownloadAndInstallAppUpdateUseCase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield


private const val DEBUG_UPDATE_STEP_DELAY_MS = 280L
private const val DEBUG_UPDATE_TERMINAL_DELAY_MS = 220L

class UpdateStartupOrchestrator
    internal constructor(
        private val startupUpdateCheck: suspend () -> AppUpdateInfo?,
    ) {
        constructor(appUpdateChecker: AppUpdateChecker) : this(
            startupUpdateCheck = appUpdateChecker::checkForStartupUpdate,
        )

        private val _dialogState = MutableStateFlow<AppUpdateDialogState?>(null)
        val dialogState: StateFlow<AppUpdateDialogState?> = _dialogState.asStateFlow()

        private var startupCheckConsumed = false
        private var activeStartupCheckJob: Job? = null

        fun triggerStartupCheck(scope: CoroutineScope) {
            if (startupCheckConsumed || activeStartupCheckJob?.isActive == true) {
                return
            }
            val startupCheckJob =
                scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val updateResult = runSuspendCatching { startupUpdateCheck() }
                        if (updateResult.isSuccess) {
                            startupCheckConsumed = true
                            updateResult.getOrNull()?.let { info ->
                                _dialogState.value = info.toDialogState()
                            }
                        }
                    } finally {
                        if (activeStartupCheckJob === coroutineContext[Job]) {
                            activeStartupCheckJob = null
                        }
                    }
                }
            activeStartupCheckJob = startupCheckJob
            startupCheckJob.start()
        }

        fun dismissUpdateDialog() {
            _dialogState.value = null
        }

        fun consumeUpdateDialog(): AppUpdateDialogState? {
            val update = _dialogState.value
            _dialogState.value = null
            return update
        }
    }

class AppUpdateDownloadSession(
    private val context: Context,
    private val downloadAndInstallAppUpdateUseCase: DownloadAndInstallAppUpdateUseCase,
    private val cancelAppUpdateDownloadUseCase: CancelAppUpdateDownloadUseCase,
    private val scope: CoroutineScope,
) {
    private val _progressDialogState = MutableStateFlow<AppUpdateProgressDialogState?>(null)
    val progressDialogState: StateFlow<AppUpdateProgressDialogState?> = _progressDialogState.asStateFlow()

    private var activeDownloadJob: Job? = null
    private var retainedCandidate: AppUpdateDialogState? = null

    fun start(update: AppUpdateDialogState) {
        if (activeDownloadJob?.isActive == true) {
            return
        }
        retainedCandidate = update
        val downloadJob =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    yield()
                    publishState(update = update, installState = AppUpdateInstallState.Preparing)
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
                    if (activeDownloadJob === coroutineContext[Job]) {
                        activeDownloadJob = null
                    }
                }
            }
        activeDownloadJob = downloadJob
        downloadJob.start()
    }

    fun retry() {
        val update = retainedCandidate ?: return
        start(update)
    }

    fun cancel() {
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

internal fun AppUpdateInfo.toDialogState(): AppUpdateDialogState =
    AppUpdateDialogState(
        url = url,
        version = version,
        releaseNotes = releaseNotes,
        apkDownloadUrl = apkDownloadUrl,
        apkFileName = apkFileName,
        apkSizeBytes = apkSizeBytes,
        expectedPackageName = expectedPackageName,
        expectedVersionName = expectedVersionName,
        expectedVersionCode = expectedVersionCode,
    )

internal fun AppUpdateDialogState.toAppUpdateInfo(): AppUpdateInfo =
    AppUpdateInfo(
        url = url,
        version = version,
        releaseNotes = releaseNotes,
        apkDownloadUrl = apkDownloadUrl,
        apkFileName = apkFileName,
        apkSizeBytes = apkSizeBytes,
        expectedPackageName = expectedPackageName,
        expectedVersionName = expectedVersionName,
        expectedVersionCode = expectedVersionCode,
    )
