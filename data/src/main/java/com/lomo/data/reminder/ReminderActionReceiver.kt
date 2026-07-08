package com.lomo.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lomo.domain.repository.ReminderCoordinator
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ReminderActionReceiver : BroadcastReceiver(), KoinComponent {
    private val asyncRunner: ReminderAsyncRunner by inject()
    private val reminderCoordinator: ReminderCoordinator by inject()
    private val reminderNotifier: ReminderNotifier by inject()

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val memoId = intent.getStringExtra(ReminderIntents.EXTRA_MEMO_ID) ?: return
        val tokenRaw = intent.getStringExtra(ReminderIntents.EXTRA_TOKEN_RAW) ?: return
        val action = intent.action ?: return
        val notificationId = ReminderRequestCodePolicy.notificationId(memoId, tokenRaw)
        val pendingResult = goAsync()

        asyncRunner.launch(pendingResult) {
            when (action) {
                ReminderIntents.ACTION_SNOOZE -> {
                    reminderCoordinator.snooze(memoId, tokenRaw)
                    reminderNotifier.cancel(notificationId)
                }
                ReminderIntents.ACTION_DONE -> {
                    reminderCoordinator.markDone(memoId, tokenRaw)
                    reminderNotifier.cancel(notificationId)
                }
                else -> Unit
            }
        }
    }
}
