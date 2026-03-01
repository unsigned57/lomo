package com.lomo.data.git

import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.GitSyncStatus
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.BranchTrackingStatus
import timber.log.Timber

@Singleton
internal class GitSyncQueryTestCoordinator
    @Inject
    constructor(
        private val credentialStrategy: GitCredentialStrategy,
        private val fileHistoryReader: GitFileHistoryReader,
    ) {
    fun getFileHistory(
        rootDir: File,
        filename: String,
        maxCount: Int = 50,
    ): List<GitFileHistoryEntry> =
        fileHistoryReader.getFileHistory(rootDir = rootDir, filename = filename, maxCount = maxCount)

    fun getStatus(rootDir: File): GitSyncStatus {
        val gitDir = File(rootDir, ".git")
        if (!gitDir.exists()) {
            return emptyStatus()
        }

        return try {
            Git.open(rootDir).use { g ->
                val status = g.status().call()
                val hasChanges = status.hasUncommittedChanges() || status.untracked.isNotEmpty()

                var ahead = 0
                var behind = 0
                try {
                    val trackingStatus = BranchTrackingStatus.of(g.repository, g.repository.branch)
                    if (trackingStatus != null) {
                        ahead = trackingStatus.aheadCount
                        behind = trackingStatus.behindCount
                    }
                } catch (_: Exception) {
                    // No tracking info available.
                }

                GitSyncStatus(
                    hasLocalChanges = hasChanges,
                    aheadCount = ahead,
                    behindCount = behind,
                    lastSyncTime = null,
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to get git status")
            emptyStatus()
        }
    }

    fun testConnection(remoteUrl: String): GitSyncResult {
        val credentials = credentialStrategy.credentialProviders()
            ?: return GitSyncResult.Error(GitSyncErrorMessages.PAT_REQUIRED)
        return try {
            credentialStrategy.runWithCredentialFallback(credentials, "ls-remote") { provider ->
                Git.lsRemoteRepository()
                    .setRemote(remoteUrl)
                    .setCredentialsProvider(provider)
                    .setHeads(true)
                    .call()
            }
            GitSyncResult.Success("Connection successful")
        } catch (e: Exception) {
            Timber.w(e, "Connection test failed")
            GitSyncResult.Error("Connection failed: ${e.message}", e)
        }
    }

    private fun emptyStatus(): GitSyncStatus =
        GitSyncStatus(
            hasLocalChanges = false,
            aheadCount = 0,
            behindCount = 0,
            lastSyncTime = null,
        )
}
