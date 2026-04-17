package com.lomo.data.git

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncConflictTextMerge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.PersonIdent
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

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
                runNonFatalCatching {
                    val gitDir = File(rootDir, ".git")
                    if (gitDir.exists()) {
                        gitDir.deleteRecursively()
                    }
                    GitSyncResult.Success("Repository reset")
                }.getOrElse { error ->
                    Timber.e(error, "Failed to reset repository")
                    GitSyncResult.Error("Reset failed: ${error.message}", error)
                }
            }

        suspend fun resetLocalBranchToRemote(
            rootDir: File,
            remoteUrl: String,
        ): GitSyncResult =
            withContext(Dispatchers.IO) {
                val credentials =
                    credentialStrategy.credentialProviders()
                        ?: return@withContext GitSyncResult.Error(GitSyncErrorMessages.PAT_REQUIRED)

                val gitDir = File(rootDir, ".git")
                if (!gitDir.exists()) {
                    return@withContext GitSyncResult.Error(NOT_GIT_REPOSITORY_MESSAGE)
                }

                primitives.cleanStaleLockFiles(rootDir)

                runNonFatalCatching {
                    Git.open(rootDir).use { git ->
                        primitives.ensureRemote(git, remoteUrl)

                        credentialStrategy.runWithCredentialFallback(credentials, "Fetch before reset") { provider ->
                            git
                                .fetch()
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

                        git
                            .reset()
                            .setMode(ResetCommand.ResetType.HARD)
                            .setRef(remoteRef.name)
                            .call()
                        git
                            .clean()
                            .setCleanDirectories(true)
                            .setForce(true)
                            .call()
                    }

                    GitSyncResult.Success("Local branch reset to remote.")
                }.getOrElse { error ->
                    Timber.e(error, "Failed to reset local branch to remote")
                    GitSyncResult.Error(
                        "Failed to reset local branch to remote: ${error.message}",
                        error,
                    )
                }
            }

        suspend fun forcePushLocalToRemote(
            rootDir: File,
            remoteUrl: String,
            onPushingState: () -> Unit,
        ): ForcePushOutcome =
            withContext(Dispatchers.IO) {
                val credentials =
                    credentialStrategy.credentialProviders()
                        ?: return@withContext ForcePushOutcome(
                            result = GitSyncResult.Error(GitSyncErrorMessages.PAT_REQUIRED),
                            syncedAtMs = null,
                        )

                val gitDir = File(rootDir, ".git")
                if (!gitDir.exists()) {
                    return@withContext ForcePushOutcome(
                        result = GitSyncResult.Error(NOT_GIT_REPOSITORY_MESSAGE),
                        syncedAtMs = null,
                    )
                }

                primitives.cleanStaleLockFiles(rootDir)

                runNonFatalCatching {
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
                }.getOrElse { error ->
                    Timber.e(error, "Failed to force push local branch")
                    ForcePushOutcome(
                        result = GitSyncResult.Error("Failed to force push local branch: ${error.message}", error),
                        syncedAtMs = null,
                    )
                }
            }

        suspend fun applyConflictResolution(
            rootDir: File,
            remoteUrl: String,
            resolution: SyncConflictResolution,
            conflictSet: SyncConflictSet,
        ): GitSyncResult =
            withContext(Dispatchers.IO) {
                val credentials =
                    credentialStrategy.credentialProviders()
                        ?: return@withContext GitSyncResult.Error(GitSyncErrorMessages.PAT_REQUIRED)

                val gitDir = File(rootDir, ".git")
                if (!gitDir.exists()) {
                    return@withContext GitSyncResult.Error(NOT_GIT_REPOSITORY_MESSAGE)
                }

                primitives.cleanStaleLockFiles(rootDir)

                runNonFatalCatching {
                    Git.open(rootDir).use { g ->
                        primitives.ensureRemote(g, remoteUrl)

                        for (file in conflictSet.files) {
                            val choice = resolution.perFileChoices[file.relativePath]
                                ?: SyncConflictResolutionChoice.KEEP_LOCAL
                            val content = when (choice) {
                                SyncConflictResolutionChoice.KEEP_LOCAL -> file.localContent
                                SyncConflictResolutionChoice.KEEP_REMOTE -> file.remoteContent
                                SyncConflictResolutionChoice.MERGE_TEXT ->
                                    SyncConflictTextMerge.merge(
                                        localText = file.localContent,
                                        remoteText = file.remoteContent,
                                        localLastModified = file.localLastModified,
                                        remoteLastModified = file.remoteLastModified,
                                    )
                                        ?: error("Unable to merge conflict for ${file.relativePath}")
                                SyncConflictResolutionChoice.SKIP_FOR_NOW ->
                                    return@withContext GitSyncResult.Error(
                                        "Git conflicts do not support deferring files for later resolution",
                                    )
                            } ?: continue

                            val target = File(rootDir, file.relativePath)
                            target.parentFile?.mkdirs()
                            target.writeText(content, Charsets.UTF_8)
                        }

                        g.add().addFilepattern(".").call()
                        g
                            .commit()
                            .setAuthor(PersonIdent("Lomo", "lomo@local"))
                            .setCommitter(PersonIdent("Lomo", "lomo@local"))
                            .setMessage("resolve: sync conflict resolution")
                            .call()

                        val branch = g.repository.branch ?: "main"
                        primitives.tryPush(
                            git = g,
                            credentialStrategy = credentialStrategy,
                            credentials = credentials,
                            branch = branch,
                            successMessage = "Conflicts resolved and pushed",
                        )
                    }
                }.getOrElse { error ->
                    Timber.e(error, "Failed to apply conflict resolution")
                    GitSyncResult.Error("Failed to apply conflict resolution: ${error.message}", error)
                }
            }
    }

private const val NOT_GIT_REPOSITORY_MESSAGE = "Not a git repository. Please initialize first."
