package com.lomo.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lomo.domain.repository.ReminderCoordinator
import com.lomo.domain.repository.MarkdownReminderRepository
import com.lomo.domain.repository.MarkdownWorkspaceRepository
import com.lomo.domain.repository.MemoQueryRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ReminderAlarmReceiver : BroadcastReceiver(), KoinComponent {
    private val asyncRunner: ReminderAsyncRunner by inject()
    private val reminderCoordinator: ReminderCoordinator by inject()
    private val reminderNotifier: ReminderNotifier by inject()
    private val memoQueryRepository: MemoQueryRepository by inject()
    private val markdownReminderRepository: MarkdownReminderRepository by inject()
    private val markdownWorkspaceRepository: MarkdownWorkspaceRepository by inject()

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ReminderIntents.ACTION_FIRE) return
        val memoId = intent.getStringExtra(ReminderIntents.EXTRA_MEMO_ID) ?: return
        val reminderId = intent.getStringExtra(ReminderIntents.EXTRA_REMINDER_ID) ?: return
        val pendingResult = goAsync()

        asyncRunner.launch(pendingResult) {
            val memo = memoQueryRepository.getMemoById(memoId) ?: return@launch
            val marker =
                markdownReminderRepository
                    .remindersForMemo(memoId)
                    .singleOrNull { it.reference.opaqueId == reminderId }
                    ?: return@launch
            if (marker.isExhausted) return@launch
            val title = markdownWorkspaceRepository.renderMarkdown(memo.content).plainText.take(80)
            val launchIntent =
                context.packageManager.getLaunchIntentForPackage(context.packageName)
                    ?: Intent()
            reminderNotifier.showFor(memoId, marker, title, launchIntent)
            reminderCoordinator.recordFired(memoId, reminderId)
        }
    }
}
