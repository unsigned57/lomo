package com.lomo.data.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lomo.domain.repository.RecordingSession
import com.lomo.domain.model.RecordingSessionState
import com.lomo.domain.usecase.CreateMemoUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

@AndroidEntryPoint
class RecordingActionReceiver : BroadcastReceiver() {
    @Inject lateinit var recordingSession: RecordingSession

    @Inject lateinit var createMemoUseCase: CreateMemoUseCase

    @Inject lateinit var recordingNotifier: RecordingNotifier

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action ?: return
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            try {
                when (action) {
                    RecordingIntents.ACTION_STOP -> handleStop()
                    RecordingIntents.ACTION_CANCEL -> recordingSession.cancelRecording()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.e(error, "Failed to handle recording action: %s", action)
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    private suspend fun handleStop() {
        val state = recordingSession.state.value as? RecordingSessionState.Recording
        val startedAtMillis = state?.startedAtMillis ?: System.currentTimeMillis()
        val markdown = recordingSession.stopRecording()
        if (markdown.isNullOrBlank()) return
        val memo = createMemoUseCase(content = markdown, timestampMillis = startedAtMillis)
        recordingNotifier.showSavedConfirmation(
            memoId = memo.id,
            openIntent = Intent(),
        )
    }
}
