package com.lomo.data.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lomo.domain.repository.RecordingSession
import com.lomo.domain.model.RecordingSessionState
import com.lomo.domain.usecase.CreateMemoUseCase
import com.lomo.domain.usecase.DispatcherProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import timber.log.Timber

class RecordingActionReceiver : BroadcastReceiver(), KoinComponent {
    private val recordingSession: RecordingSession by inject()
    private val createMemoUseCase: CreateMemoUseCase by inject()
    private val recordingNotifier: RecordingNotifier by inject()
    private val dispatcherProvider: DispatcherProvider by inject()

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action ?: return
        val pendingResult = goAsync()
        // behavior-contract: unmanaged-scope-ok: receiver-owned short job cancelled after pendingResult.finish
        val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)

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
        val draftId = state?.draftId
        val markdown = recordingSession.stopRecording()
        if (markdown.isNullOrBlank() || draftId == null) return
        val memo = createMemoUseCase(
            com.lomo.domain.model.MemoCreateAttempt(
                operationId = com.lomo.domain.model.MemoOperationId("recording-$startedAtMillis"),
                draftId = draftId,
                content = markdown,
                timestampMillis = startedAtMillis,
            ),
        )
        recordingNotifier.showSavedConfirmation(
            memoId = memo.id,
            openIntent = Intent(),
        )
    }
}
