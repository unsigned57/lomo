package com.lomo.data.git

import com.lomo.domain.model.GitSyncResult
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
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

@Singleton
class GitRepositoryPrimitives
    @Inject
    constructor() {
        fun cleanStaleLockFiles(rootDir: File) {
            val lockFile = File(rootDir, ".git/index.lock")
            if (lockFile.exists()) {
                val deleted = lockFile.delete()
                Timber.w("Cleaned stale index.lock: deleted=%b", deleted)
            }
        }

        fun ensureRemote(
            git: Git,
            remoteUrl: String,
        ) {
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
            val lastCommit = git.log().setMaxCount(1).call().firstOrNull() ?: return false
            if (!lastCommit.fullMessage.contains("via Lomo")) return false

            return try {
                val tracking = BranchTrackingStatus.of(git.repository, branch)
                tracking != null && tracking.aheadCount > 0
            } catch (_: Exception) {
                false
            }
        }

        fun abortRebaseQuietly(git: Git) {
            try {
                git.rebase()
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
            return try {
                val pushResults = credentialStrategy.runWithCredentialFallback(credentials, "Push") { provider ->
                    git.push()
                        .setRemote("origin")
                        .setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch"))
                        .setCredentialsProvider(provider)
                        .setForce(force)
                        .call()
                }
                for (pushResult in pushResults) {
                    for (update in pushResult.remoteUpdates) {
                        when (update.status) {
                            RemoteRefUpdate.Status.OK,
                            RemoteRefUpdate.Status.UP_TO_DATE,
                            -> Unit
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

        fun readFileAtCommit(
            git: Git,
            commit: RevCommit,
            filename: String,
        ): String? {
            return try {
                val treeWalk = TreeWalk.forPath(git.repository, filename, commit.tree) ?: return null
                val objectId: ObjectId = treeWalk.getObjectId(0)
                val loader = git.repository.open(objectId)
                String(loader.bytes, Charsets.UTF_8)
            } catch (e: Exception) {
                Timber.w(e, "Failed to read %s at commit %s", filename, commit.name)
                null
            }
        }
    }
