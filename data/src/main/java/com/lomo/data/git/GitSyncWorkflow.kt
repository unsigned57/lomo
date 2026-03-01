package com.lomo.data.git

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.SyncEngineState
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.merge.MergeStrategy
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import timber.log.Timber

@Singleton
class GitSyncWorkflow
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
        private val credentialStrategy: GitCredentialStrategy,
        private val primitives: GitRepositoryPrimitives,
    ) {
        suspend fun initOrClone(
            rootDir: File,
            remoteUrl: String,
        ): GitSyncResult =
            withContext(Dispatchers.IO) {
                val credentials = credentialStrategy.credentialProviders()
                    ?: return@withContext GitSyncResult.Error(GitSyncErrorMessages.PAT_REQUIRED)

                primitives.cleanStaleLockFiles(rootDir)

                val gitDir = File(rootDir, ".git")
                if (gitDir.exists()) {
                    val git = Git.open(rootDir)
                    git.use { g ->
                        primitives.ensureRemote(g, remoteUrl)
                    }
                    return@withContext GitSyncResult.Success("Repository opened")
                }

                try {
                    credentialStrategy.runWithCredentialFallback(credentials, "Clone") { provider ->
                        Git.cloneRepository()
                            .setURI(remoteUrl)
                            .setDirectory(rootDir)
                            .setCredentialsProvider(provider)
                            .setCloneAllBranches(false)
                            .setDepth(1)
                            .call()
                            .close()
                    }
                    primitives.ensureGitignore(rootDir)
                    GitSyncResult.Success("Repository cloned")
                } catch (e: Exception) {
                    Timber.w(e, "Clone failed, initializing new repo")
                    val residualGitDir = File(rootDir, ".git")
                    if (residualGitDir.exists()) {
                        residualGitDir.deleteRecursively()
                    }
                    initNewRepo(rootDir, remoteUrl, credentials)
                }
            }

        suspend fun commitLocal(rootDir: File): GitSyncResult =
            withContext(Dispatchers.IO) {
                val gitDir = File(rootDir, ".git")
                if (!gitDir.exists()) return@withContext GitSyncResult.Error("Not a git repository")

                primitives.cleanStaleLockFiles(rootDir)

                val git = Git.open(rootDir)
                git.use { g ->
                    val author = authorIdent()
                    val branch = g.repository.branch ?: "main"

                    g.add().addFilepattern(".").call()
                    g.add().addFilepattern(".").setUpdate(true).call()

                    val status = g.status().call()
                    val hasChanges = status.hasUncommittedChanges() ||
                        status.added.isNotEmpty() ||
                        status.changed.isNotEmpty() ||
                        status.removed.isNotEmpty()

                    if (!hasChanges) return@withContext GitSyncResult.Success("Nothing to commit")

                    val shouldAmend = primitives.shouldAmendLastCommit(g, branch)
                    g.commit()
                        .setAuthor(author)
                        .setCommitter(author)
                        .setMessage(syncCommitMessage())
                        .setAmend(shouldAmend)
                        .call()

                    GitSyncResult.Success(if (shouldAmend) "Amended local commit" else "Committed locally")
                }
            }

        suspend fun sync(
            rootDir: File,
            remoteUrl: String,
            onSyncingState: (SyncEngineState.Syncing) -> Unit,
        ): GitSyncResult =
            withContext(Dispatchers.IO) {
                val credentials = credentialStrategy.credentialProviders()
                    ?: return@withContext GitSyncResult.Error(GitSyncErrorMessages.PAT_REQUIRED)

                primitives.cleanStaleLockFiles(rootDir)

                val gitDir = File(rootDir, ".git")
                if (!gitDir.exists()) {
                    return@withContext GitSyncResult.Error("Not a git repository. Please initialize first.")
                }

                val git = Git.open(rootDir)
                git.use { g ->
                    primitives.ensureRemote(g, remoteUrl)

                    val author = authorIdent()
                    val branch = g.repository.branch ?: "main"

                    onSyncingState(SyncEngineState.Syncing.Committing)
                    g.add().addFilepattern(".").call()
                    g.add().addFilepattern(".").setUpdate(true).call()

                    val status = g.status().call()
                    val hasChanges = status.hasUncommittedChanges() ||
                        status.added.isNotEmpty() ||
                        status.changed.isNotEmpty() ||
                        status.removed.isNotEmpty()

                    if (hasChanges) {
                        val shouldAmend = primitives.shouldAmendLastCommit(g, branch)
                        g.commit()
                            .setAuthor(author)
                            .setCommitter(author)
                            .setMessage(syncCommitMessage())
                            .setAmend(shouldAmend)
                            .call()
                    }

                    onSyncingState(SyncEngineState.Syncing.Pulling)
                    try {
                        credentialStrategy.runWithCredentialFallback(credentials, "Fetch") { provider ->
                            g.fetch()
                                .setRemote("origin")
                                .setCredentialsProvider(provider)
                                .call()
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Fetch failed")
                        onSyncingState(SyncEngineState.Syncing.Pushing)
                        return@withContext primitives.tryPush(
                            git = g,
                            credentialStrategy = credentialStrategy,
                            credentials = credentials,
                            branch = branch,
                            successMessage = "Synced (push only)",
                        )
                    }

                    val remoteBranch = "origin/$branch"
                    val remoteBranchRef = g.repository.resolve(remoteBranch)
                    if (remoteBranchRef != null) {
                        try {
                            val rebaseResult = g.rebase()
                                .setUpstream(remoteBranch)
                                .setStrategy(MergeStrategy.RECURSIVE)
                                .call()

                            when (rebaseResult.status) {
                                RebaseResult.Status.OK,
                                RebaseResult.Status.UP_TO_DATE,
                                RebaseResult.Status.FAST_FORWARD,
                                RebaseResult.Status.NOTHING_TO_COMMIT,
                                -> Unit
                                RebaseResult.Status.CONFLICTS,
                                RebaseResult.Status.STOPPED,
                                RebaseResult.Status.FAILED,
                                RebaseResult.Status.UNCOMMITTED_CHANGES,
                                RebaseResult.Status.EDIT,
                                -> {
                                    Timber.w("Rebase requires manual resolution: %s", rebaseResult.status)
                                    primitives.abortRebaseQuietly(g)
                                    return@withContext GitSyncResult.Error(
                                        "Sync halted: rebase ${rebaseResult.status.name} detected. " +
                                            "Remote changes were preserved; please resolve conflicts manually.",
                                    )
                                }
                                RebaseResult.Status.ABORTED -> {
                                    Timber.w("Rebase aborted")
                                    return@withContext GitSyncResult.Error(
                                        "Sync halted: rebase aborted. Remote changes were preserved.",
                                    )
                                }
                                else -> {
                                    Timber.w("Unexpected rebase status: ${rebaseResult.status}")
                                    primitives.abortRebaseQuietly(g)
                                    return@withContext GitSyncResult.Error(
                                        "Sync failed: unexpected rebase status ${rebaseResult.status}.",
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "Rebase failed, aborting without auto-merge")
                            primitives.abortRebaseQuietly(g)
                            return@withContext GitSyncResult.Error(
                                "Sync failed during rebase and was aborted to avoid overwriting remote changes: ${e.message}",
                                e,
                            )
                        }
                    }

                    onSyncingState(SyncEngineState.Syncing.Pushing)
                    primitives.tryPush(
                        git = g,
                        credentialStrategy = credentialStrategy,
                        credentials = credentials,
                        branch = branch,
                        successMessage = "Synced",
                    )
                }
            }

        private suspend fun authorIdent(): PersonIdent {
            val name = dataStore.gitAuthorName.first()
            val email = dataStore.gitAuthorEmail.first()
            return PersonIdent(name.ifBlank { "Lomo" }, email.ifBlank { "lomo@local" })
        }

        private suspend fun initNewRepo(
            rootDir: File,
            remoteUrl: String,
            credentials: List<UsernamePasswordCredentialsProvider>,
        ): GitSyncResult {
            val git = Git.init().setDirectory(rootDir).call()
            git.use { g ->
                primitives.ensureRemote(g, remoteUrl)
                primitives.ensureGitignore(rootDir)

                g.add().addFilepattern(".").call()
                val author = authorIdent()
                g.commit()
                    .setAuthor(author)
                    .setCommitter(author)
                    .setMessage("init: first commit via Lomo")
                    .call()

                val currentBranch = g.repository.branch
                if (currentBranch != "main") {
                    g.branchRename().setOldName(currentBranch).setNewName("main").call()
                }

                try {
                    credentialStrategy.runWithCredentialFallback(credentials, "Initial push") { provider ->
                        g.push()
                            .setRemote("origin")
                            .setCredentialsProvider(provider)
                            .call()
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Initial push failed (remote may not exist)")
                }
            }
            return GitSyncResult.Success("Repository initialized")
        }

        private fun syncCommitMessage(): String {
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            return "sync: $timestamp via Lomo"
        }
    }
