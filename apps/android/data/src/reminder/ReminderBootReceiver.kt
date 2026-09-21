package com.lomo.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ReminderBootReceiver : BroadcastReceiver(), KoinComponent {
    private val asyncRunner: ReminderAsyncRunner by inject()
    private val rebuildDemand: ReminderRebuildDemand by inject()

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (!ReminderRescheduleBroadcast.accepts(intent.action)) {
            return
        }
        val pendingResult = goAsync()
        asyncRunner.launch(pendingResult) {
            rebuildDemand.enqueue()
        }
    }
}
