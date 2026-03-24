package com.lomo.data.git

import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.GitSyncResult
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.merge.MergeStrategy
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.TreeWalk
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitRepositoryPrimitives
    @Inject
    constructor() {
        fun cleanStaleLockFiles(rootDir: File) {
            val lockFile = File(rootDir, ".git/index.lock")
            if (lockFile.exists()) {
                val ageMs = System.currentTimeMillis() - lockFile.lastModified()
                if (ageMs > STALE_LOCK_THRESHOLD_MS) {
                    val deleted = lockFile.delete()
                    Timber.w("Cleaned stale index.lock (age=%dms): deleted=%b", ageMs, deleted)
                } else {
                    Timber.d("index.lock exists but is recent (age=%dms), skipping", ageMs)
                }
            }
        }

        fun ensureRemote(
            git: Git,
            remoteUrl: String,
        ) {
            val config = git.repository.config
            val existingUrl = config.getString(REMOTE_SECTION, REMOTE_NAME, REMOTE_URL_KEY)
            if (existingUrl != remoteUrl) {
                if (existingUrl != null) {
                    git
                        .remoteSetUrl()
                        .setRemoteName(REMOTE_NAME)
                        .setRemoteUri(URIish(remoteUrl))
                        .call()
                } else {
                    git
                        .remoteAdd()
                        .setName(REMOTE_NAME)
                        .setUri(URIish(remoteUrl))
                        .call()
                }
            }
        }

        fun ensureGitignore(rootDir: File) {
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

        fun shouldAmendLastCommit(
            git: Git,
            branch: String,
        ): Boolean {
            val lastCommit =
                git
                    .log()
                    .setMaxCount(1)
                    .call()
                    .firstOrNull()
            val shouldInspectTracking =
                lastCommit?.fullMessage?.contains(LOMO_COMMIT_MARKER) == true

            return if (!shouldInspectTracking) {
                false
            } else {
                try {
                    val tracking = BranchTrackingStatus.of(git.repository, branch)
                    tracking != null && tracking.aheadCount > 0
                } catch (_: Exception) {
                    false
                }
            }
        }

        fun abortRebaseQuietly(git: Git) {
            try {
                git
                    .rebase()
                    .setOperation(RebaseCommand.Operation.ABORT)
                    .call()
            } catch (_: Exception) {
                // Ignore when no rebase state exists.
            }
        }

        fun tryPush(
            git: Git,
            credentialStrategy: GitCredentialStrategy,
            credentials: List<UsernamePasswordCredentialsProvider>,
            branch: String,
            successMessage: String,
            force: Boolean = false,
        ): GitSyncResult {
            return runNonFatalCatching {
                val pushResults =
                    credentialStrategy.runWithCredentialFallback(credentials, "Push") { provider ->
                        git
                            .push()
                            .setRemote(REMOTE_NAME)
                            .setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch"))
                            .setCredentialsProvider(provider)
                            .setForce(force)
                            .call()
                    }
                val failure =
                    pushResults
                        .asSequence()
                        .flatMap { pushResult -> pushResult.remoteUpdates.asSequence() }
                        .mapNotNull(::pushFailureForUpdate)
                        .firstOrNull()

                failure ?: GitSyncResult.Success(successMessage)
            }.getOrElse { error ->
                Timber.e(error, "Push failed")
                GitSyncResult.Error("Push failed: ${error.message}", error)
            }
        }

        fun readFileAtCommit(
            git: Git,
            commit: RevCommit,
            filename: String,
        ): String? {
            return runNonFatalCatching {
                val treeWalk = TreeWalk.forPath(git.repository, filename, commit.tree) ?: return null
                val objectId: ObjectId = treeWalk.getObjectId(0)
                val loader = git.repository.open(objectId)
                String(loader.bytes, Charsets.UTF_8)
            }.getOrElse { error ->
                Timber.w(error, "Failed to read %s at commit %s", filename, commit.name)
                null
            }
        }

        companion object {
            private const val STALE_LOCK_THRESHOLD_MS = 5 * 60 * 1000L // 5 minutes
            private const val LOMO_COMMIT_MARKER = "via Lomo"
            private const val REMOTE_NAME = "origin"
            private const val REMOTE_SECTION = "remote"
            private const val REMOTE_URL_KEY = "url"
        }
    }

private fun pushFailureForUpdate(update: RemoteRefUpdate): GitSyncResult.Error? =
    when (update.status) {
        RemoteRefUpdate.Status.OK,
        RemoteRefUpdate.Status.UP_TO_DATE,
        -> null

        RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD ->
            GitSyncResult.Error("Push rejected: non-fast-forward. Remote has diverged.")

        RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED ->
            GitSyncResult.Error("Push rejected: remote ref was updated during push.")

        RemoteRefUpdate.Status.REJECTED_NODELETE ->
            GitSyncResult.Error("Push rejected: remote does not allow branch deletion.")

        RemoteRefUpdate.Status.REJECTED_OTHER_REASON ->
            GitSyncResult.Error("Push rejected: ${update.message ?: "unknown reason"}")

        else ->
            GitSyncResult.Error("Push failed with status: ${update.status}")
    }
