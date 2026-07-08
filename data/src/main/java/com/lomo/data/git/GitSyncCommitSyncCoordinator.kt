package com.lomo.data.git
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.UnifiedSyncPhase
import java.io.File
internal class GitSyncCommitSyncCoordinator
constructor(
        private val workflow: GitSyncWorkflow,
        private val dataStore: LomoDataStore,
    ) {
        data class SyncOutcome(
            val result: GitSyncResult,
            val syncedAtMs: Long?,
        )
        suspend fun commitLocal(rootDir: File): GitSyncResult = workflow.commitLocal(rootDir)
        suspend fun sync(
            rootDir: File,
            remoteUrl: String,
            onSyncingState: (UnifiedSyncPhase) -> Unit,
        ): SyncOutcome {
            val result = workflow.sync(rootDir, remoteUrl, onSyncingState)
            return if (result is GitSyncResult.Success) {
                val now = System.currentTimeMillis()
                dataStore.updateGitLastSyncTime(now)
                SyncOutcome(result = result, syncedAtMs = now)
            } else {
                SyncOutcome(result = result, syncedAtMs = null)
            }
        }
    }
