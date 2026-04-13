package com.lomo.data.repository

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.worker.GitSyncScheduler
import com.lomo.data.worker.S3SyncScheduler
import com.lomo.data.worker.SyncWorker
import com.lomo.data.worker.WebDavSyncScheduler
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.repository.SyncPolicyRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncPolicyRepositoryImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dataStore: LomoDataStore,
        private val gitSyncScheduler: GitSyncScheduler,
        private val webDavSyncScheduler: WebDavSyncScheduler,
        private val s3SyncScheduler: S3SyncScheduler,
    ) : SyncPolicyRepository {
        override fun ensureCoreSyncActive() {
            val syncRequest =
                PeriodicWorkRequestBuilder<SyncWorker>(Duration.ofHours(1))
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                            .build(),
                    ).build()

            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(
                    SyncWorker.WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    syncRequest,
                )
        }

        override fun observeRemoteSyncBackend(): Flow<SyncBackendType> =
            dataStore.syncBackendType.map(::syncBackendFromPreference)

        override suspend fun setRemoteSyncBackend(type: SyncBackendType) {
            dataStore.updateSyncBackendType(type.preferenceValue)
            when (type) {
                SyncBackendType.NONE -> {
                    dataStore.updateGitSyncEnabled(false)
                    dataStore.updateWebDavSyncEnabled(false)
                    dataStore.updateS3SyncEnabled(false)
                }

                SyncBackendType.GIT -> {
                    dataStore.updateGitSyncEnabled(true)
                    dataStore.updateWebDavSyncEnabled(false)
                    dataStore.updateS3SyncEnabled(false)
                }

                SyncBackendType.WEBDAV -> {
                    dataStore.updateGitSyncEnabled(false)
                    dataStore.updateWebDavSyncEnabled(true)
                    dataStore.updateS3SyncEnabled(false)
                }

                SyncBackendType.S3 -> {
                    dataStore.updateGitSyncEnabled(false)
                    dataStore.updateWebDavSyncEnabled(false)
                    dataStore.updateS3SyncEnabled(true)
                }

                SyncBackendType.INBOX -> {
                    dataStore.updateGitSyncEnabled(false)
                    dataStore.updateWebDavSyncEnabled(false)
                    dataStore.updateS3SyncEnabled(false)
                }
            }
        }

        override suspend fun applyRemoteSyncPolicy() {
            when (syncBackendFromPreference(dataStore.syncBackendType.first())) {
                SyncBackendType.NONE -> {
                    gitSyncScheduler.cancel()
                    webDavSyncScheduler.cancel()
                    s3SyncScheduler.cancel()
                }

                SyncBackendType.GIT -> {
                    webDavSyncScheduler.cancel()
                    s3SyncScheduler.cancel()
                    gitSyncScheduler.reschedule()
                }

                SyncBackendType.WEBDAV -> {
                    gitSyncScheduler.cancel()
                    s3SyncScheduler.cancel()
                    webDavSyncScheduler.reschedule()
                }

                SyncBackendType.S3 -> {
                    gitSyncScheduler.cancel()
                    webDavSyncScheduler.cancel()
                    s3SyncScheduler.reschedule()
                }

                SyncBackendType.INBOX -> {
                    gitSyncScheduler.cancel()
                    webDavSyncScheduler.cancel()
                    s3SyncScheduler.cancel()
                }
            }
        }
    }

private val SyncBackendType.preferenceValue: String
    get() = name.lowercase(java.util.Locale.ROOT)

private fun syncBackendFromPreference(value: String): SyncBackendType =
    SyncBackendType.entries.firstOrNull { it.preferenceValue == value.lowercase(java.util.Locale.ROOT) }
        ?: SyncBackendType.NONE
