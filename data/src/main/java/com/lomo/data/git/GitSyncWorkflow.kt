package com.lomo.data.git

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncEngineState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.merge.MergeStrategy
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.TreeWalk
import timber.log.Timber
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

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
                val credentials =
                    credentialStrategy.credentialProviders()
                        ?: return@withContext GitSyncResult.Error(GitSyncErrorMessages.PAT_REQUIRED)

                primitives.cleanStaleLockFiles(rootDir)

                val gitDir = File(rootDir, GIT_METADATA_DIRECTORY)
                if (gitDir.exists()) {
                    Git.open(rootDir).use { git ->
                        primitives.ensureRemote(git, remoteUrl)
                    }
                    return@withContext GitSyncResult.Success("Repository opened")
                }

                runNonFatalCatching {
                    credentialStrategy.runWithCredentialFallback(credentials, "Clone") { provider ->
                        Git
                            .cloneRepository()
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
                }.getOrElse { error ->
                    Timber.w(error, "Clone failed, initializing new repo")
                    val residualGitDir = File(rootDir, GIT_METADATA_DIRECTORY)
                    if (residualGitDir.exists()) {
                        residualGitDir.deleteRecursively()
                    }
                    initNewRepo(rootDir, remoteUrl, credentials)
                }
            }

        suspend fun commitLocal(rootDir: File): GitSyncResult =
            withContext(Dispatchers.IO) {
                val gitDir = File(rootDir, GIT_METADATA_DIRECTORY)
                if (!gitDir.exists()) {
                    return@withContext GitSyncResult.Error("Not a git repository")
                }

                primitives.cleanStaleLockFiles(rootDir)

                Git.open(rootDir).use { git ->
                    val branch = git.repository.branch ?: DEFAULT_BRANCH
                    stageAllChanges(git)
                    if (!hasPendingChanges(git)) {
                        return@withContext GitSyncResult.Success("Nothing to commit")
                    }

                    val author = authorIdent(dataStore)
                    val shouldAmend = primitives.shouldAmendLastCommit(git, branch)
                    git
                        .commit()
                        .setAuthor(author)
                        .setCommitter(author)
                        .setMessage(syncCommitMessage())
                        .setAmend(shouldAmend)
                        .call()

                    GitSyncResult.Success(
                        if (shouldAmend) {
                            "Amended local commit"
                        } else {
                            "Committed locally"
                        },
                    )
                }
            }

        suspend fun sync(
            rootDir: File,
            remoteUrl: String,
            onSyncingState: (SyncEngineState.Syncing) -> Unit,
        ): GitSyncResult =
            withContext(Dispatchers.IO) {
                val credentials =
                    credentialStrategy.credentialProviders()
                        ?: return@withContext GitSyncResult.Error(GitSyncErrorMessages.PAT_REQUIRED)

                primitives.cleanStaleLockFiles(rootDir)

                val gitDir = File(rootDir, GIT_METADATA_DIRECTORY)
                if (!gitDir.exists()) {
                    return@withContext GitSyncResult.Error("Not a git repository. Please initialize first.")
                }

                Git.open(rootDir).use { git ->
                    primitives.ensureRemote(git, remoteUrl)

                    val branch = git.repository.branch ?: DEFAULT_BRANCH
                    stageAndCommitIfNeeded(
                        git = git,
                        branch = branch,
                        dataStore = dataStore,
                        primitives = primitives,
                        onSyncingState = onSyncingState,
                    )

                    val fetchFallback =
                        fetchRemoteOrPushOnly(
                            git = git,
                            branch = branch,
                            credentials = credentials,
                            credentialStrategy = credentialStrategy,
                            primitives = primitives,
                            onSyncingState = onSyncingState,
                        )
                    if (fetchFallback != null) {
                        return@withContext fetchFallback
                    }

                    val rebaseResult =
                        rebaseOntoRemote(
                            git = git,
                            rootDir = rootDir,
                            primitives = primitives,
                            readFileFromRef = ::readFileFromRef,
                        )
                    if (rebaseResult != null) {
                        return@withContext rebaseResult
                    }

                    onSyncingState(SyncEngineState.Syncing.Pushing)
                    primitives.tryPush(
                        git = git,
                        credentialStrategy = credentialStrategy,
                        credentials = credentials,
                        branch = branch,
                        successMessage = "Synced",
                    )
                }
            }

        private suspend fun initNewRepo(
            rootDir: File,
            remoteUrl: String,
            credentials: List<UsernamePasswordCredentialsProvider>,
        ): GitSyncResult {
            val git = Git.init().setDirectory(rootDir).call()
            git.use { repository ->
                primitives.ensureRemote(repository, remoteUrl)
                primitives.ensureGitignore(rootDir)

                repository.add().addFilepattern(".").call()
                val author = authorIdent(dataStore)
                repository
                    .commit()
                    .setAuthor(author)
                    .setCommitter(author)
                    .setMessage("init: first commit via Lomo")
                    .call()

                val currentBranch = repository.repository.branch
                if (currentBranch != DEFAULT_BRANCH) {
                    repository
                        .branchRename()
                        .setOldName(currentBranch)
                        .setNewName(DEFAULT_BRANCH)
                        .call()
                }

                runNonFatalCatching {
                    credentialStrategy.runWithCredentialFallback(credentials, "Initial push") { provider ->
                        repository
                            .push()
                            .setRemote(REMOTE_NAME)
                            .setCredentialsProvider(provider)
                            .call()
                    }
                }.onFailure { error ->
                    Timber.w(error, "Initial push failed (remote may not exist)")
                }
            }
            return GitSyncResult.Success("Repository initialized")
        }

        private fun readFileFromRef(
            repo: Repository,
            refName: String,
            path: String,
        ): String? {
            val ref = repo.resolve(refName)
            var content: String? = null

            if (ref != null) {
                val revWalk = RevWalk(repo)
                try {
                    val commit = revWalk.parseCommit(ref)
                    val treeWalk = TreeWalk.forPath(repo, path, commit.tree)
                    if (treeWalk != null) {
                        val objectId = treeWalk.getObjectId(0)
                        val loader = repo.open(objectId)
                        content = String(loader.bytes, Charsets.UTF_8)
                    }
                } finally {
                    revWalk.dispose()
                }
            }

            return content
        }
    }

private suspend fun stageAndCommitIfNeeded(
    git: Git,
    branch: String,
    dataStore: LomoDataStore,
    primitives: GitRepositoryPrimitives,
    onSyncingState: (SyncEngineState.Syncing) -> Unit,
) {
    onSyncingState(SyncEngineState.Syncing.Committing)
    stageAllChanges(git)
    if (!hasPendingChanges(git)) {
        return
    }

    val author = authorIdent(dataStore)
    val shouldAmend = primitives.shouldAmendLastCommit(git, branch)
    git
        .commit()
        .setAuthor(author)
        .setCommitter(author)
        .setMessage(syncCommitMessage())
        .setAmend(shouldAmend)
        .call()
}

private fun stageAllChanges(git: Git) {
    git.add().addFilepattern(".").call()
    git
        .add()
        .addFilepattern(".")
        .setUpdate(true)
        .call()
}

private fun hasPendingChanges(git: Git): Boolean {
    val status = git.status().call()
    return status.hasUncommittedChanges() ||
        status.added.isNotEmpty() ||
        status.changed.isNotEmpty() ||
        status.removed.isNotEmpty()
}

private fun fetchRemoteOrPushOnly(
    git: Git,
    branch: String,
    credentials: List<UsernamePasswordCredentialsProvider>,
    credentialStrategy: GitCredentialStrategy,
    primitives: GitRepositoryPrimitives,
    onSyncingState: (SyncEngineState.Syncing) -> Unit,
): GitSyncResult? {
    onSyncingState(SyncEngineState.Syncing.Pulling)
    return runNonFatalCatching {
        credentialStrategy.runWithCredentialFallback(credentials, "Fetch") { provider ->
            git
                .fetch()
                .setRemote(REMOTE_NAME)
                .setCredentialsProvider(provider)
                .call()
        }
        null
    }.getOrElse { error ->
        Timber.w(error, "Fetch failed")
        onSyncingState(SyncEngineState.Syncing.Pushing)
        primitives.tryPush(
            git = git,
            credentialStrategy = credentialStrategy,
            credentials = credentials,
            branch = branch,
            successMessage = "Synced (push only)",
        )
    }
}

private fun rebaseOntoRemote(
    git: Git,
    rootDir: File,
    primitives: GitRepositoryPrimitives,
    readFileFromRef: (Repository, String, String) -> String?,
): GitSyncResult? {
    val remoteBranch = "$REMOTE_NAME/${git.repository.branch ?: DEFAULT_BRANCH}"
    if (git.repository.resolve(remoteBranch) == null) {
        return null
    }

    return runNonFatalCatching {
        val rebaseResult =
            git
                .rebase()
                .setUpstream(remoteBranch)
                .setStrategy(MergeStrategy.RECURSIVE)
                .call()
        handleRebaseResult(git, rootDir, remoteBranch, rebaseResult, primitives, readFileFromRef)
    }.getOrElse { error ->
        Timber.w(error, "Rebase failed, aborting without auto-merge")
        primitives.abortRebaseQuietly(git)
        GitSyncResult.Error(
            "Sync failed during rebase and was aborted to avoid overwriting remote changes: " +
                error.message,
            error,
        )
    }
}

private fun handleRebaseResult(
    git: Git,
    rootDir: File,
    remoteBranch: String,
    rebaseResult: RebaseResult,
    primitives: GitRepositoryPrimitives,
    readFileFromRef: (Repository, String, String) -> String?,
): GitSyncResult? =
    when (rebaseResult.status) {
        RebaseResult.Status.OK,
        RebaseResult.Status.UP_TO_DATE,
        RebaseResult.Status.FAST_FORWARD,
        RebaseResult.Status.NOTHING_TO_COMMIT,
        -> null

        RebaseResult.Status.CONFLICTS,
        RebaseResult.Status.STOPPED,
        RebaseResult.Status.FAILED,
        RebaseResult.Status.UNCOMMITTED_CHANGES,
        RebaseResult.Status.EDIT,
        -> buildConflictResult(
            git = git,
            rootDir = rootDir,
            remoteBranch = remoteBranch,
            status = rebaseResult.status,
            primitives = primitives,
            readFileFromRef = readFileFromRef,
        )

        RebaseResult.Status.ABORTED -> {
            Timber.w("Rebase aborted")
            GitSyncResult.Error("Sync halted: rebase aborted. Remote changes were preserved.")
        }

        else -> {
            Timber.w("Unexpected rebase status: ${rebaseResult.status}")
            primitives.abortRebaseQuietly(git)
            GitSyncResult.Error("Sync failed: unexpected rebase status ${rebaseResult.status}.")
        }
    }

private fun buildConflictResult(
    git: Git,
    rootDir: File,
    remoteBranch: String,
    status: RebaseResult.Status,
    primitives: GitRepositoryPrimitives,
    readFileFromRef: (Repository, String, String) -> String?,
): GitSyncResult {
    Timber.w("Rebase requires manual resolution: %s", status)
    val conflictFiles =
        buildConflictFiles(
            repository = git.repository,
            rootDir = rootDir,
            remoteBranch = remoteBranch,
            conflictingPaths = git.status().call().conflicting,
            readFileFromRef = readFileFromRef,
        )
    primitives.abortRebaseQuietly(git)

    return if (conflictFiles.isNotEmpty()) {
        GitSyncResult.Conflict(
            message = "Sync halted: ${conflictFiles.size} conflicting file(s) detected.",
            conflicts =
                SyncConflictSet(
                    source = SyncBackendType.GIT,
                    files = conflictFiles,
                    timestamp = System.currentTimeMillis(),
                ),
        )
    } else {
        GitSyncResult.Error(
            "Sync halted: rebase ${status.name} detected. " +
                "Remote changes were preserved; please resolve conflicts manually.",
        )
    }
}

private fun buildConflictFiles(
    repository: Repository,
    rootDir: File,
    remoteBranch: String,
    conflictingPaths: Set<String>,
    readFileFromRef: (Repository, String, String) -> String?,
): List<SyncConflictFile> =
    conflictingPaths.map { path ->
        val localFile = File(rootDir, path)
        SyncConflictFile(
            relativePath = path,
            localContent = readLocalFile(rootDir, path),
            remoteContent = readFileFromRef(repository, remoteBranch, path),
            isBinary = !path.endsWith(".md"),
            localLastModified = localFile.takeIf(File::exists)?.lastModified(),
        )
    }

private fun readLocalFile(
    rootDir: File,
    path: String,
): String? =
    try {
        File(rootDir, path).readText(Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

private suspend fun authorIdent(dataStore: LomoDataStore): PersonIdent {
    val name = dataStore.gitAuthorName.first()
    val email = dataStore.gitAuthorEmail.first()
    return PersonIdent(name.ifBlank { "Lomo" }, email.ifBlank { "lomo@local" })
}

private fun syncCommitMessage(): String {
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    return "sync: $timestamp via Lomo"
}

private const val DEFAULT_BRANCH = "main"
private const val GIT_METADATA_DIRECTORY = ".git"
private const val REMOTE_NAME = "origin"
