package com.lomo.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.ReminderCoordinator
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

private const val TAG = "ReminderActionReceiver"

class ReminderActionReceiver : BroadcastReceiver(), KoinComponent {
    private val asyncRunner: ReminderAsyncRunner by inject()
    private val reminderCoordinator: ReminderCoordinator by inject()
    private val reminderNotifier: ReminderNotifier by inject()
    private val engineReadiness: EngineReadinessRepository by inject()

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val memoId = intent.getStringExtra(ReminderIntents.EXTRA_MEMO_ID) ?: return
        val reminderId = intent.getStringExtra(ReminderIntents.EXTRA_REMINDER_ID) ?: return
        val action = intent.action ?: return
        val occurrenceId = intent.getStringExtra(ReminderIntents.EXTRA_OCCURRENCE_ID)
        val notificationId = ReminderRequestCodePolicy.notificationId(memoId, reminderId)
        val pendingResult = goAsync()

        asyncRunner.launch(
            pendingResult,
            onResult = { result ->
                if (result is ReminderReceiverWorkResult.Failed) {
                    Timber.tag(TAG).w(result.cause, "Reminder action %s failed for memo %s", action, memoId)
                }
            },
        ) {
            // Notification actions can spawn a cold process; engine acquisition is explicit,
            // so snooze/done must request the engine before touching engine-backed repos.
            if (engineReadiness.requestEngineStart() !is EngineReadiness.Ready) {
                Timber.tag(TAG).i(
                    "Reminder action %s skipped: workspace engine is not ready (memo=%s)",
                    action,
                    memoId,
                )
                return@launch
            }
            when (action) {
                ReminderIntents.ACTION_SNOOZE -> {
                    reminderCoordinator.snooze(memoId, reminderId)
                    reminderNotifier.cancel(notificationId, occurrenceId)
                }
                ReminderIntents.ACTION_DONE -> {
                    reminderCoordinator.markDone(memoId, reminderId)
                    reminderNotifier.cancel(notificationId, occurrenceId)
                }
                else -> Unit
            }
        }
    }
}
