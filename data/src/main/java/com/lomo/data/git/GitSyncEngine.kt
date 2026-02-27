package com.lomo.data.git

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.GitSyncState
import com.lomo.domain.model.GitSyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.merge.MergeStrategy
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import timber.log.Timber
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitSyncEngine
    @Inject
    constructor(
        private val credentialStore: GitCredentialStore,
        private val dataStore: LomoDataStore,
    ) {
        private val mutex = Mutex()
        private val _syncState = MutableStateFlow<GitSyncState>(GitSyncState.Idle)
        val syncState: StateFlow<GitSyncState> = _syncState

        @Volatile
        private var cachedCredentialIndex: Int = -1

        private fun credentialProviders(): List<UsernamePasswordCredentialsProvider>? {
            val token = credentialStore.getToken()?.trim().orEmpty()
            if (token.isBlank()) return null

            return listOf(
                // Common GitHub style for token-based HTTPS auth.
                UsernamePasswordCredentialsProvider("x-access-token", token),
                // Legacy behavior kept as fallback for compatibility with existing setups.
                UsernamePasswordCredentialsProvider(token, ""),
                UsernamePasswordCredentialsProvider(token, "x-oauth-basic"),
            )
        }

        fun markNotConfigured() {
            _syncState.value = GitSyncState.NotConfigured
        }

        fun markError(message: String) {
            _syncState.value = GitSyncState.Error(message, System.currentTimeMillis())
        }

        private suspend fun authorIdent(): PersonIdent {
            val name = dataStore.gitAuthorName.first()
            val email = dataStore.gitAuthorEmail.first()
            return PersonIdent(name.ifBlank { "Lomo" }, email.ifBlank { "lomo@local" })
        }

        private fun cleanStaleLockFiles(rootDir: File) {
            val lockFile = File(rootDir, ".git/index.lock")
            if (lockFile.exists()) {
                val deleted = lockFile.delete()
                Timber.w("Cleaned stale index.lock: deleted=%b", deleted)
            }
        }

        suspend fun initOrClone(rootDir: File, remoteUrl: String): GitSyncResult =
            mutex.withLock {
                withContext(Dispatchers.IO) {
                    _syncState.value = GitSyncState.Initializing
                    try {
                        val result = doInitOrClone(rootDir, remoteUrl)
                        when (result) {
                            is GitSyncResult.Success ->
                                _syncState.value = GitSyncState.Success(
                                    System.currentTimeMillis(),
                                    result.message,
                                )
                            is GitSyncResult.Error ->
                                _syncState.value = GitSyncState.Error(
                                    result.message,
                                    System.currentTimeMillis(),
                                )
                            else -> _syncState.value = GitSyncState.Idle
                        }
                        result
                    } catch (e: Exception) {
                        Timber.e(e, "Git init/clone failed")
                        val msg = e.message ?: "Unknown error"
                        _syncState.value = GitSyncState.Error(msg, System.currentTimeMillis())
                        GitSyncResult.Error(msg, e)
                    }
                }
            }

        private suspend fun doInitOrClone(rootDir: File, remoteUrl: String): GitSyncResult {
            val credentials = credentialProviders()
                ?: return GitSyncResult.Error("No Personal Access Token configured")

            cleanStaleLockFiles(rootDir)

            val gitDir = File(rootDir, ".git")
            if (gitDir.exists()) {
                // Already a git repo, just ensure remote is set
                val git = Git.open(rootDir)
                git.use { g ->
                    ensureRemote(g, remoteUrl)
                }
                return GitSyncResult.Success("Repository opened")
            }

            // Try to clone first (remote might have content)
            return try {
                runWithCredentialFallback(credentials, "Clone") { provider ->
                    Git.cloneRepository()
                        .setURI(remoteUrl)
                        .setDirectory(rootDir)
                        .setCredentialsProvider(provider)
                        .setCloneAllBranches(false)
                        .setDepth(1)
                        .call()
                        .close()
                }
                ensureGitignore(rootDir)
                GitSyncResult.Success("Repository cloned")
            } catch (e: Exception) {
                // Clone may fail if repo is empty or doesn't exist yet
                Timber.w(e, "Clone failed, initializing new repo")
                // Clean up any residual .git directory left by the failed clone
                val residualGitDir = File(rootDir, ".git")
                if (residualGitDir.exists()) {
                    residualGitDir.deleteRecursively()
                }
                initNewRepo(rootDir, remoteUrl, credentials)
            }
        }

        private suspend fun initNewRepo(
            rootDir: File,
            remoteUrl: String,
            credentials: List<UsernamePasswordCredentialsProvider>,
        ): GitSyncResult {
            val git = Git.init().setDirectory(rootDir).call()
            git.use { g ->
                ensureRemote(g, remoteUrl)
                ensureGitignore(rootDir)

                g.add().addFilepattern(".").call()

                val author = authorIdent()
                g.commit()
                    .setAuthor(author)
                    .setCommitter(author)
                    .setMessage("init: first commit via Lomo")
                    .call()

                // Ensure we're on main branch
                val currentBranch = g.repository.branch
                if (currentBranch != "main") {
                    g.branchRename().setOldName(currentBranch).setNewName("main").call()
                }

                try {
                    runWithCredentialFallback(credentials, "Initial push") { provider ->
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

        private fun ensureRemote(git: Git, remoteUrl: String) {
            val config = git.repository.config
            val existingUrl = config.getString("remote", "origin", "url")
            if (existingUrl != remoteUrl) {
                if (existingUrl != null) {
                    git.remoteSetUrl()
                        .setRemoteName("origin")
                        .setRemoteUri(URIish(remoteUrl))
                        .call()
                } else {
                    git.remoteAdd()
                        .setName("origin")
                        .setUri(URIish(remoteUrl))
                        .call()
                }
            }
        }

        private fun ensureGitignore(rootDir: File) {
            val gitignore = File(rootDir, ".gitignore")
            if (!gitignore.exists()) {
                gitignore.writeText(
                    """
                    |.trash/
                    |*.db
                    |*.db-journal
                    |*.db-wal
                    """.trimMargin() + "\n",
                )
            }
        }

        suspend fun sync(rootDir: File, remoteUrl: String): GitSyncResult =
            mutex.withLock {
                withContext(Dispatchers.IO) {
                    _syncState.value = GitSyncState.Syncing.Committing
                    try {
                        val result = doSync(rootDir, remoteUrl)
                        when (result) {
                            is GitSyncResult.Success -> {
                                val now = System.currentTimeMillis()
                                dataStore.updateGitLastSyncTime(now)
                                _syncState.value = GitSyncState.Success(now, result.message)
                            }
                            is GitSyncResult.Error ->
                                _syncState.value = GitSyncState.Error(
                                    result.message,
                                    System.currentTimeMillis(),
                                )
                            else -> _syncState.value = GitSyncState.Idle
                        }
                        result
                    } catch (e: Exception) {
                        Timber.e(e, "Git sync failed")
                        val msg = e.message ?: "Unknown error"
                        _syncState.value = GitSyncState.Error(msg, System.currentTimeMillis())
                        GitSyncResult.Error(msg, e)
                    }
                }
            }

        private suspend fun doSync(rootDir: File, remoteUrl: String): GitSyncResult {
            val credentials = credentialProviders()
                ?: return GitSyncResult.Error("No Personal Access Token configured")

            cleanStaleLockFiles(rootDir)

            val gitDir = File(rootDir, ".git")
            if (!gitDir.exists()) {
                return GitSyncResult.Error("Not a git repository. Please initialize first.")
            }

            val git = Git.open(rootDir)
            git.use { g ->
                // Ensure remote URL matches current settings
                ensureRemote(g, remoteUrl)

                val author = authorIdent()
                val branch = g.repository.branch ?: "main"

                // 1. Stage all changes
                _syncState.value = GitSyncState.Syncing.Committing
                g.add().addFilepattern(".").call()
                // Also stage deletions
                g.add().addFilepattern(".").setUpdate(true).call()

                // 2. Commit local changes if any
                val status = g.status().call()
                val hasChanges = status.hasUncommittedChanges() ||
                    status.added.isNotEmpty() ||
                    status.changed.isNotEmpty() ||
                    status.removed.isNotEmpty()

                if (hasChanges) {
                    val timestamp = LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    g.commit()
                        .setAuthor(author)
                        .setCommitter(author)
                        .setMessage("sync: $timestamp via Lomo")
                        .call()
                }

                // 3. Fetch remote
                _syncState.value = GitSyncState.Syncing.Pulling
                try {
                    runWithCredentialFallback(credentials, "Fetch") { provider ->
                        g.fetch()
                            .setRemote("origin")
                            .setCredentialsProvider(provider)
                            .call()
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Fetch failed")
                    // If fetch fails (e.g. no remote branch yet), just push
                    _syncState.value = GitSyncState.Syncing.Pushing
                    return tryPush(g, credentials, branch, "Synced (push only)")
                }

                // 4. Check if remote branch exists
                val remoteBranch = "origin/$branch"
                val remoteBranchRef = g.repository.resolve(remoteBranch)

                if (remoteBranchRef != null) {
                    // Rebase local onto remote (use RECURSIVE — fair 3-way merge,
                    // falls back to local-priority merge on conflict).
                    try {
                        val rebaseResult = g.rebase()
                            .setUpstream(remoteBranch)
                            .setStrategy(MergeStrategy.RECURSIVE)
                            .call()

                        when (rebaseResult.status) {
                            RebaseResult.Status.OK,
                            RebaseResult.Status.UP_TO_DATE,
                            RebaseResult.Status.FAST_FORWARD,
                            -> {
                                // Success, proceed to push
                            }
                            RebaseResult.Status.CONFLICTS,
                            RebaseResult.Status.STOPPED,
                            -> {
                                // Resolve conflicts by keeping local (abort rebase, merge instead)
                                Timber.w("Rebase conflict, aborting and using merge")
                                g.rebase()
                                    .setOperation(RebaseCommand.Operation.ABORT)
                                    .call()
                                // Fallback to merge with ours strategy
                                g.merge()
                                    .include(remoteBranchRef)
                                    .setStrategy(MergeStrategy.OURS)
                                    .setCommit(true)
                                    .setMessage("merge: resolve conflicts (local priority) via Lomo")
                                    .call()
                            }
                            else -> {
                                Timber.w("Unexpected rebase status: ${rebaseResult.status}")
                                g.rebase()
                                    .setOperation(RebaseCommand.Operation.ABORT)
                                    .call()
                            }
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Rebase failed, falling back to merge")
                        try {
                            g.rebase()
                                .setOperation(RebaseCommand.Operation.ABORT)
                                .call()
                        } catch (_: Exception) {
                            // Ignore abort errors
                        }
                        g.merge()
                            .include(remoteBranchRef)
                            .setStrategy(MergeStrategy.OURS)
                            .setCommit(true)
                            .setMessage("merge: resolve conflicts (local priority) via Lomo")
                            .call()
                    }
                }

                // 5. Push to remote
                _syncState.value = GitSyncState.Syncing.Pushing
                return tryPush(g, credentials, branch, "Synced")
            }
        }

        private inline fun <T> runWithCredentialFallback(
            providers: List<UsernamePasswordCredentialsProvider>,
            operation: String,
            block: (UsernamePasswordCredentialsProvider) -> T,
        ): T {
            // Try cached credential first
            val cached = cachedCredentialIndex
            if (cached in providers.indices) {
                try {
                    val result = block(providers[cached])
                    return result
                } catch (e: Exception) {
                    Timber.w(e, "$operation failed with cached credential strategy #${cached + 1}, resetting cache")
                    cachedCredentialIndex = -1
                }
            }

            var lastError: Exception? = null
            providers.forEachIndexed { index, provider ->
                if (index == cached) return@forEachIndexed // already tried above
                try {
                    val result = block(provider)
                    cachedCredentialIndex = index
                    return result
                } catch (e: Exception) {
                    lastError = e
                    Timber.w(e, "$operation failed with credential strategy #${index + 1}")
                }
            }

            throw lastError ?: IllegalStateException("$operation failed without a captured exception")
        }

        private fun tryPush(
            git: Git,
            credentials: List<UsernamePasswordCredentialsProvider>,
            branch: String,
            successMessage: String,
        ): GitSyncResult {
            return try {
                val pushResults = runWithCredentialFallback(credentials, "Push") { provider ->
                    git.push()
                        .setRemote("origin")
                        .setRefSpecs(
                            org.eclipse.jgit.transport.RefSpec("refs/heads/$branch:refs/heads/$branch"),
                        )
                        .setCredentialsProvider(provider)
                        .call()
                }
                // Check each RemoteRefUpdate status
                for (pushResult in pushResults) {
                    for (update in pushResult.remoteUpdates) {
                        when (update.status) {
                            RemoteRefUpdate.Status.OK,
                            RemoteRefUpdate.Status.UP_TO_DATE,
                            -> { /* success */ }
                            RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD ->
                                return GitSyncResult.Error(
                                    "Push rejected: non-fast-forward. Remote has diverged.",
                                )
                            RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED ->
                                return GitSyncResult.Error(
                                    "Push rejected: remote ref was updated during push.",
                                )
                            RemoteRefUpdate.Status.REJECTED_NODELETE ->
                                return GitSyncResult.Error(
                                    "Push rejected: remote does not allow branch deletion.",
                                )
                            RemoteRefUpdate.Status.REJECTED_OTHER_REASON ->
                                return GitSyncResult.Error(
                                    "Push rejected: ${update.message ?: "unknown reason"}",
                                )
                            else ->
                                return GitSyncResult.Error(
                                    "Push failed with status: ${update.status}",
                                )
                        }
                    }
                }
                GitSyncResult.Success(successMessage)
            } catch (e: Exception) {
                Timber.e(e, "Push failed")
                GitSyncResult.Error("Push failed: ${e.message}", e)
            }
        }

        fun getStatus(rootDir: File): GitSyncStatus {
            val gitDir = File(rootDir, ".git")
            if (!gitDir.exists()) {
                return GitSyncStatus(
                    hasLocalChanges = false,
                    aheadCount = 0,
                    behindCount = 0,
                    lastSyncTime = null,
                )
            }

            return try {
                val git = Git.open(rootDir)
                git.use { g ->
                    val status = g.status().call()
                    val hasChanges = status.hasUncommittedChanges() ||
                        status.untracked.isNotEmpty()

                    var ahead = 0
                    var behind = 0
                    try {
                        val trackingStatus = BranchTrackingStatus.of(
                            g.repository,
                            g.repository.branch,
                        )
                        if (trackingStatus != null) {
                            ahead = trackingStatus.aheadCount
                            behind = trackingStatus.behindCount
                        }
                    } catch (_: Exception) {
                        // No tracking info available
                    }

                    GitSyncStatus(
                        hasLocalChanges = hasChanges,
                        aheadCount = ahead,
                        behindCount = behind,
                        lastSyncTime = null, // Filled by repository layer from DataStore
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to get git status")
                GitSyncStatus(
                    hasLocalChanges = false,
                    aheadCount = 0,
                    behindCount = 0,
                    lastSyncTime = null,
                )
            }
        }

        fun testConnection(remoteUrl: String): GitSyncResult {
            val credentials = credentialProviders()
                ?: return GitSyncResult.Error("No Personal Access Token configured")
            return try {
                runWithCredentialFallback(credentials, "ls-remote") { provider ->
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

        suspend fun resetRepository(rootDir: File): GitSyncResult =
            mutex.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        val gitDir = File(rootDir, ".git")
                        if (gitDir.exists()) {
                            gitDir.deleteRecursively()
                        }
                        _syncState.value = GitSyncState.Idle
                        GitSyncResult.Success("Repository reset")
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to reset repository")
                        GitSyncResult.Error("Reset failed: ${e.message}", e)
                    }
                }
            }
    }
