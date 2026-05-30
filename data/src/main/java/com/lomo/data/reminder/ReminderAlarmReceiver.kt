package com.lomo.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lomo.domain.repository.ReminderCoordinator
import com.lomo.domain.repository.MemoQueryRepository
import com.lomo.domain.usecase.ParseRemindersUseCase
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ReminderAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var asyncRunner: ReminderAsyncRunner

    @Inject lateinit var reminderCoordinator: ReminderCoordinator

    @Inject lateinit var reminderNotifier: ReminderNotifier

    @Inject lateinit var memoQueryRepository: MemoQueryRepository

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
