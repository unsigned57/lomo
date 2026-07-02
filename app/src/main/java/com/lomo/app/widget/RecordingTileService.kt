package com.lomo.app.widget

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat
import com.lomo.app.MainActivity
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageArea
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.model.RecordingSessionState
import com.lomo.domain.repository.SecuritySessionPolicy
import com.lomo.domain.usecase.CreateMemoUseCase
import com.lomo.domain.usecase.RecordingSessionUseCase
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@AndroidEntryPoint
class RecordingTileService : TileService() {
    @Inject lateinit var recordingSessionUseCase: RecordingSessionUseCase

    @Inject lateinit var securitySessionPolicy: SecuritySessionPolicy

    @Inject lateinit var directorySettingsRepository: DirectorySettingsRepository

    @Inject lateinit var createMemoUseCase: CreateMemoUseCase

    private val policy = RecordingTileClickPolicy()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateCollectionJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        stateCollectionJob?.cancel()
        stateCollectionJob =
            serviceScope.launch {
                recordingSessionUseCase.state.collect { state ->
                    updateTile(state)
                }
            }
    }

    override fun onStopListening() {
        stateCollectionJob?.cancel()
        stateCollectionJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        serviceScope.launch {
            try {
                handleClick()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.e(error, "Failed to handle recording tile click")
                updateTile(recordingSessionUseCase.state.value)
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun handleClick() {
        when (
            policy.decide(
                preconditions = resolvePreconditions(),
                state = recordingSessionUseCase.state.value,
            )
        ) {
            TileClickAction.StartRecording -> recordingSessionUseCase.startRecording()
            TileClickAction.StopRecording -> stopRecordingAndSaveMemo()
            TileClickAction.LaunchMainActivityWithStartRecording -> launchMainActivityStartRecording()
        }
        updateTile(recordingSessionUseCase.state.value)
    }

    private suspend fun resolvePreconditions(): RecordingPreconditions =
        RecordingPreconditions(
            hasRecordAudioPermission =
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED,
            isVoiceWorkspaceReady =
                withContext(Dispatchers.IO) {
                    directorySettingsRepository.currentLocation(StorageArea.VOICE) != null
                },
            isAppLockSatisfied = securitySessionPolicy.isAppLockSatisfied(),
        )

    private suspend fun stopRecordingAndSaveMemo(): Memo? {
        val startedAtMillis =
            (recordingSessionUseCase.state.value as? RecordingSessionState.Recording)
                ?.startedAtMillis
                ?: System.currentTimeMillis()
        val markdown = recordingSessionUseCase.stopRecording()?.takeIf(String::isNotBlank) ?: return null
        return createMemoUseCase(
            content = markdown,
            timestampMillis = startedAtMillis,
        )
    }

    private fun launchMainActivityStartRecording() {
        startMainActivityAndCollapse(
            requestCode = START_RECORDING_REQUEST_CODE,
            intent =
                Intent(this, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_START_RECORDING
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
        )
    }

    private fun startMainActivityAndCollapse(
        requestCode: Int,
        intent: Intent,
    ) {
        val wrapper =
            PendingIntentActivityWrapper(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT,
                false,
            )
        TileServiceCompat.startActivityAndCollapse(this, wrapper)
    }

    private fun updateTile(state: RecordingSessionState) {
        qsTile?.let { tile ->
            tile.state =
                when (state) {
                    RecordingSessionState.Idle -> Tile.STATE_INACTIVE
                    is RecordingSessionState.Recording -> Tile.STATE_ACTIVE
                }
            tile.updateTile()
        }
    }

    private companion object {
        const val START_RECORDING_REQUEST_CODE = 51_002
    }
}
