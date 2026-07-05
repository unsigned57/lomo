package com.lomo.app.widget

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat
import com.lomo.app.R
import com.lomo.app.TrustedLaunchIntents
import com.lomo.domain.model.RecordingSessionState
import com.lomo.domain.usecase.RecordingSessionUseCase
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class RecordingTileService : TileService() {
    @Inject lateinit var recordingSessionUseCase: RecordingSessionUseCase

    @Inject lateinit var trustedLaunchIntents: TrustedLaunchIntents

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
        try {
            handleClick()
        } catch (error: Exception) {
            Timber.e(error, "Failed to handle recording tile click")
            updateTile(recordingSessionUseCase.state.value)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun handleClick() {
        when (
            policy.decide(recordingSessionUseCase.state.value)
        ) {
            TileClickAction.LaunchStartRecording ->
                launchTrustedRecordingAction(
                    requestCode = START_RECORDING_REQUEST_CODE,
                    intent = trustedLaunchIntents.trustedQuickSettingsStartRecordingIntent(),
                )
            TileClickAction.LaunchStopRecording ->
                launchTrustedRecordingAction(
                    requestCode = STOP_RECORDING_REQUEST_CODE,
                    intent = trustedLaunchIntents.trustedQuickSettingsStopRecordingIntent(),
                )
        }
        updateTile(recordingSessionUseCase.state.value)
    }

    private fun launchTrustedRecordingAction(
        requestCode: Int,
        intent: Intent,
    ) {
        startMainActivityAndCollapse(
            requestCode = requestCode,
            intent = intent,
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
            val presentation = policy.presentation(state)
            tile.state =
                when (presentation) {
                    RecordingTilePresentation.Start -> Tile.STATE_INACTIVE
                    RecordingTilePresentation.Stop -> Tile.STATE_ACTIVE
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val labelRes =
                    when (presentation) {
                        RecordingTilePresentation.Start -> R.string.tile_start_recording_label
                        RecordingTilePresentation.Stop -> R.string.tile_stop_recording_label
                    }
                val label = getString(labelRes)
                tile.label = label
                tile.contentDescription = label
            }
            tile.updateTile()
        }
    }

    private companion object {
        const val START_RECORDING_REQUEST_CODE = 51_002
        const val STOP_RECORDING_REQUEST_CODE = 51_003
    }
}
