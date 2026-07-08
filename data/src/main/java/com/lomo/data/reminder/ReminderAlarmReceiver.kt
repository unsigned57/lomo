package com.lomo.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lomo.domain.repository.ReminderCoordinator
import com.lomo.domain.repository.MemoQueryRepository
import com.lomo.domain.usecase.ParseRemindersUseCase
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ReminderAlarmReceiver : BroadcastReceiver(), KoinComponent {
    private val asyncRunner: ReminderAsyncRunner by inject()
    private val reminderCoordinator: ReminderCoordinator by inject()
    private val reminderNotifier: ReminderNotifier by inject()
    private val memoQueryRepository: MemoQueryRepository by inject()

    private val parseReminders = ParseRemindersUseCase()

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ReminderIntents.ACTION_FIRE) return
        val memoId = intent.getStringExtra(ReminderIntents.EXTRA_MEMO_ID) ?: return
        val tokenRaw = intent.getStringExtra(ReminderIntents.EXTRA_TOKEN_RAW) ?: return
        val pendingResult = goAsync()

        asyncRunner.launch(pendingResult) {
            val memo = memoQueryRepository.getMemoById(memoId) ?: return@launch
            val marker =
                parseReminders(memo.content)
                    .firstOrNull { it.raw == tokenRaw }
                    ?: return@launch
            if (marker.isExhausted) return@launch
            val title = memo.content.lineSequence().firstOrNull { it.isNotBlank() }?.take(80).orEmpty()
            val launchIntent =
                context.packageManager.getLaunchIntentForPackage(context.packageName)
                    ?: Intent()
            reminderNotifier.showFor(memoId, marker, title, launchIntent)
            reminderCoordinator.recordFired(memoId, tokenRaw)
        }
    }
}
