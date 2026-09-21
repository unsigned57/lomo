package com.lomo.data.reminder

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class WorkManagerReminderRebuildDemand(
    private val context: Context,
) : ReminderRebuildDemand {
    override fun enqueue() {
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(
                ReminderRebuildWorker.WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReminderRebuildWorker>().build(),
            )
    }
}
