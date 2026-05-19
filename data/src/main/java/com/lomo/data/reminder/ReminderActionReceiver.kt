package com.lomo.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lomo.domain.repository.ReminderCoordinator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ReminderActionReceiver : BroadcastReceiver() {
    @Inject lateinit var reminderCoordinator: ReminderCoordinator

    @Inject lateinit var reminderNotifier: ReminderNotifier

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val memoId = intent.getStringExtra(ReminderIntents.EXTRA_MEMO_ID) ?: return
        val tokenRaw = intent.getStringExtra(ReminderIntents.EXTRA_TOKEN_RAW) ?: return
        val action = intent.action ?: return
        val notificationId = AlarmManagerReminderCoordinator.requestCodeFor(memoId, tokenRaw)
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.Default).launch {
            try {
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
            } finally {
                pendingResult.finish()
            }
        }
    }
}
