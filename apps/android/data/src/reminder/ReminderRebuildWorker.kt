package com.lomo.data.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.ReminderCoordinator
import timber.log.Timber

class ReminderRebuildWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val reminderCoordinator: ReminderCoordinator,
    private val engineReadiness: EngineReadinessRepository,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        if (engineReadiness.readiness.value !is EngineReadiness.Ready ||
            engineReadiness.workspaceAuthority.value == null
        ) {
            Timber.i("%s deferred: workspace session is not ready", WORKER_NAME)
            return Result.success()
        }
        reminderCoordinator.rebuildAll()
        return Result.success()
    }

    companion object {
        private const val WORKER_NAME: String = "ReminderRebuildWorker"
        const val WORK_NAME: String = "com.lomo.data.reminder.ReminderRebuildWorker"
    }
}
