package com.lomo.data.git

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import org.eclipse.jgit.api.Git
import timber.log.Timber

data class GitFileHistoryEntry(
    val commitHash: String,
    val commitTime: Long,
    val commitMessage: String,
    val fileContent: String,
)

@Singleton
class GitFileHistoryReader
    @Inject
    constructor(
        private val primitives: GitRepositoryPrimitives,
    ) {
        fun getFileHistory(
            rootDir: File,
            filename: String,
            maxCount: Int = 50,
        ): List<GitFileHistoryEntry> {
            val gitDir = File(rootDir, ".git")
            if (!gitDir.exists()) return emptyList()

            return try {
                val git = Git.open(rootDir)
                git.use { g ->
                    val commits =
                        g.log()
                            .addPath(filename)
                            .setMaxCount(maxCount)
                            .call()
                            .toList()

                    val result = mutableListOf<GitFileHistoryEntry>()
                    for (commit in commits) {
                        val content = primitives.readFileAtCommit(g, commit, filename) ?: continue
                        result +=
                            GitFileHistoryEntry(
                                commitHash = commit.name,
                                commitTime = commit.commitTime.toLong() * 1000L,
                                commitMessage = commit.shortMessage,
                                fileContent = content,
                            )
                    }
                    result
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to get file history for %s", filename)
                emptyList()
            }
        }
    }
