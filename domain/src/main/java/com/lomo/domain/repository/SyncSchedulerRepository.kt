package com.lomo.domain.repository

interface SyncSchedulerRepository {
    fun ensureLocalPeriodicSyncScheduled()

    suspend fun rescheduleGitAutoSync()
}
