package com.lomo.data.repository
import com.lomo.data.git.GitSyncErrorMessages
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.repository.GitSyncConflictRepository
import kotlinx.coroutines.flow.first
import timber.log.Timber
class GitSyncConflictRepositoryImpl
constructor(
        private val runtime: GitSyncRepositoryContext,
        private val support: GitSyncRepositorySupport,
        private val memoMirror: GitSyncMemoMirror,
    ) : GitSyncConflictRepository {
        override suspend fun resolveConflicts(
            resolution: SyncConflictResolution,
            conflictSet: SyncConflictSet,
        ): GitSyncResult {
            val remoteUrl = runtime.dataStore.gitRemoteUrl.first()
            if (remoteUrl.isNullOrBlank()) {
                return GitSyncResult.Error(REPOSITORY_URL_NOT_CONFIGURED_MESSAGE)
            }
            val resolvedRemoteUrl = checkNotNull(remoteUrl)
            val layout = SyncDirectoryLayout.resolve(runtime.dataStore)
            val repoDir = resolveConflictRepoDir(layout)
            if (repoDir == null) {
                return GitSyncResult.Error(MEMO_DIRECTORY_NOT_CONFIGURED_MESSAGE)
            }
            val tokenPreconditionError = support.gitTokenPreconditionError()
            if (tokenPreconditionError != null) {
                return tokenPreconditionError
            }
            val result =
                support.runGitIo {
                    runtime.gitSyncEngine.resolveConflicts(
                        repoDir,
                        resolvedRemoteUrl,
                        resolution,
                        conflictSet,
                    )
                }
            if (result is GitSyncResult.Success) {
                memoMirror.mirrorMemoFromRepo(repoDir, layout)
                runNonFatalCatching {
                    runtime.memoSynchronizer.refresh()
                }.onFailure { error ->
                    Timber.w(error, "Memo refresh after conflict resolution failed")
                }
            }
            return result
        }
        private suspend fun resolveConflictRepoDir(layout: SyncDirectoryLayout): java.io.File? {
            val directRootDir = support.resolveRootDir()
            val safRootUri = support.resolveSafRootUri()
            return when {
                directRootDir != null -> support.resolveGitRepoDir(directRootDir, layout)
                safRootUri.isNullOrBlank() -> null
                !layout.allSameDirectory -> support.resolveGitRepoDirForUri(safRootUri)
                else -> support.runGitIo { runtime.safGitMirrorBridge.mirrorDirectoryFor(safRootUri) }
            }
        }
    }
