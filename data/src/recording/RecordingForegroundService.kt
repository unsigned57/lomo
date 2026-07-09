package com.lomo.data.recording

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.lomo.domain.repository.RecordingSession
import com.lomo.domain.model.RecordingSessionState
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RecordingForegroundService : Service(), KoinComponent {
    private val recordingSession: RecordingSession by inject()

    private val recordingNotifier: RecordingNotifier by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateCollector: Job? = null

    override fun onCreate() {
        super.onCreate()
        stateCollector =
            serviceScope.launch {
                recordingSession.state.collect { state ->
                    updateForegroundNotification(state)
                    if (state is RecordingSessionState.Idle) {
                        recordingNotifier.cancelOngoing()
                        stopSelf()
                    }
                }
            }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        promoteToForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        stateCollector?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promoteToForeground() {
        val notification =
            recordingNotifier
                .buildOngoingNotification(recordingSession.durationMillis.value)
                .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                RecordingIntents.ONGOING_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(RecordingIntents.ONGOING_NOTIFICATION_ID, notification)
        }
    }

    private fun updateForegroundNotification(state: RecordingSessionState) {
        if (state !is RecordingSessionState.Recording) return
        val notification =
            recordingNotifier
                .buildOngoingNotification(recordingSession.durationMillis.value)
                .build()
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(RecordingIntents.ONGOING_NOTIFICATION_ID, notification)
    }
}
