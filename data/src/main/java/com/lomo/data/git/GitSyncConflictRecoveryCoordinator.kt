package com.lomo.data.git

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.GitSyncResult
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import timber.log.Timber

@Singleton
internal class GitSyncConflictRecoveryCoordinator
    @Inject
    constructor(
        private val workflow: GitSyncWorkflow,
        private val dataStore: LomoDataStore,
        private val credentialStrategy: GitCredentialStrategy,
        private val primitives: GitRepositoryPrimitives,
    ) {
        data class ForcePushOutcome(
            val result: GitSyncResult,
            val syncedAtMs: Long?,
        )

        suspend fun resetRepository(rootDir: File): GitSyncResult =
            withContext(Dispatchers.IO) {
                try {
                    val gitDir = File(rootDir, ".git")
                    if (gitDir.exists()) {
                        gitDir.deleteRecursively()
                    }
                    GitSyncResult.Success("Repository reset")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to reset repository")
                    GitSyncResult.Error("Reset failed: ${e.message}", e)
                }
            }

        suspend fun resetLocalBranchToRemote(
            rootDir: File,
            remoteUrl: String,
        ): GitSyncResult =
            withContext(Dispatchers.IO) {
                val credentials = credentialStrategy.credentialProviders()
                    ?: return@withContext GitSyncResult.Error(GitSyncErrorMessages.PAT_REQUIRED)

                val gitDir = File(rootDir, ".git")
                if (!gitDir.exists()) {
                    return@withContext GitSyncResult.Error("Not a git repository. Please initialize first.")
                }

                primitives.cleanStaleLockFiles(rootDir)

                try {
                    Git.open(rootDir).use { git ->
                        primitives.ensureRemote(git, remoteUrl)

                        credentialStrategy.runWithCredentialFallback(credentials, "Fetch before reset") { provider ->
                            git.fetch()
                                .setRemote("origin")
                                .setCredentialsProvider(provider)
                                .setForceUpdate(true)
                                .call()
                        }

                        val remoteRef =
                            git.repository.resolve("refs/remotes/origin/main")
                                ?: git.repository.resolve("refs/remotes/origin/master")
                                ?: return@withContext GitSyncResult.Error(
                                    "Remote branch not found. Please check repository branch configuration.",
                                )

                        primitives.abortRebaseQuietly(git)

                        git.reset()
                            .setMode(ResetCommand.ResetType.HARD)
                            .setRef(remoteRef.name)
                            .call()
                        git.clean().setCleanDirectories(true).setForce(true).call()
                    }

                    GitSyncResult.Success("Local branch reset to remote.")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to reset local branch to remote")
                    GitSyncResult.Error(
                        "Failed to reset local branch to remote: ${e.message}",
                        e,
                    )
                }
            }

        suspend fun forcePushLocalToRemote(
            rootDir: File,
            remoteUrl: String,
            onPushingState: () -> Unit,
        ): ForcePushOutcome =
            withContext(Dispatchers.IO) {
                val credentials = credentialStrategy.credentialProviders()
                    ?: return@withContext ForcePushOutcome(
                        result = GitSyncResult.Error(GitSyncErrorMessages.PAT_REQUIRED),
                        syncedAtMs = null,
                    )

                val gitDir = File(rootDir, ".git")
                if (!gitDir.exists()) {
                    return@withContext ForcePushOutcome(
                        result = GitSyncResult.Error("Not a git repository. Please initialize first."),
                        syncedAtMs = null,
                    )
                }

                primitives.cleanStaleLockFiles(rootDir)

                try {
                    val commitResult = workflow.commitLocal(rootDir)
                    if (commitResult is GitSyncResult.Error) {
                        return@withContext ForcePushOutcome(result = commitResult, syncedAtMs = null)
                    }

                    Git.open(rootDir).use { git ->
                        primitives.ensureRemote(git, remoteUrl)
                        val branch = git.repository.branch ?: "main"
                        onPushingState()

                        val pushResult =
                            primitives.tryPush(
                                git = git,
                                credentialStrategy = credentialStrategy,
                                credentials = credentials,
                                branch = branch,
                                successMessage = "Force pushed local branch to remote",
                                force = true,
                            )

                        if (pushResult is GitSyncResult.Success) {
                            val now = System.currentTimeMillis()
                            dataStore.updateGitLastSyncTime(now)
                            return@withContext ForcePushOutcome(result = pushResult, syncedAtMs = now)
                        }
                        return@withContext ForcePushOutcome(result = pushResult, syncedAtMs = null)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to force push local branch")
                    ForcePushOutcome(
                        result = GitSyncResult.Error("Failed to force push local branch: ${e.message}", e),
                        syncedAtMs = null,
                    )
                }
            }
    }
