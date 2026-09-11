package com.lomo.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lomo.domain.repository.ReminderCoordinator
import com.lomo.domain.repository.MarkdownWorkspaceRepository
import com.lomo.domain.repository.MemoQueryRepository
import com.lomo.domain.model.Memo
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ReminderAlarmReceiver : BroadcastReceiver(), KoinComponent {
    private val asyncRunner: ReminderAsyncRunner by inject()
    private val reminderCoordinator: ReminderCoordinator by inject()
    private val reminderNotifier: ReminderNotifier by inject()
    private val memoQueryRepository: MemoQueryRepository by inject()
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
            // A durable projection row can outlive an alarm (trash) or be visible during a
            // pending create. Neither state is an executable reminder fact.
            if (!reminderExecutionAllowed(memo)) return@launch
            val marker = projectedReminderFor(memo, reminderId) ?: return@launch
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

/** Only durable, active projection rows may cross into the OS notification side effect. */
internal fun reminderExecutionAllowed(memo: Memo): Boolean = !memo.isDeleted && !memo.isPending

/** Resolves the alarm identity from the same materialized memo projection used for scheduling. */
internal fun projectedReminderFor(memo: Memo, reminderId: String) =
    memo.reminders.singleOrNull { it.reference.opaqueId == reminderId }
