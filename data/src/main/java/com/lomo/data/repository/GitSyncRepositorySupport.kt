package com.lomo.data.repository

import com.lomo.data.sync.SyncDirectoryLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitSyncRepositorySupport
    @Inject
    constructor(
        private val runtime: GitSyncRepositoryContext,
    ) {
        suspend fun resolveRootDir(): File? {
            val directPath = runtime.dataStore.rootDirectory.first()
            return directPath
                ?.takeIf(String::isNotBlank)
                ?.let(::File)
                ?.takeIf { it.exists() && it.isDirectory }
        }

        suspend fun resolveSafRootUri(): String? = runtime.dataStore.rootUri.first()?.takeIf(String::isNotBlank)

        fun resolveGitRepoDir(
            userMemoDir: File,
            layout: SyncDirectoryLayout,
        ): File = if (layout.allSameDirectory) userMemoDir else internalRepoDir(sha256(userMemoDir.absolutePath))

        fun resolveGitRepoDirForUri(uri: String): File = internalRepoDir(sha256(uri))

        suspend fun prepareSafMirror(safRootUri: String): File =
            runGitIo {
                val mirrorDir = runtime.safGitMirrorBridge.mirrorDirectoryFor(safRootUri)
                runtime.safGitMirrorBridge.pullFromSaf(safRootUri, mirrorDir)
                mirrorDir
            }

        suspend fun pushSafMirror(
            safRootUri: String,
            repoDir: File,
        ) {
            runGitIo {
                runtime.safGitMirrorBridge.pushToSaf(safRootUri, repoDir)
            }
        }

        suspend fun <T> runGitIo(block: suspend () -> T): T =
            withContext(Dispatchers.IO) {
                block()
            }

        private fun internalRepoDir(hash: String): File {
            val baseDir = File(runtime.context.filesDir, GIT_SYNC_REPO_DIR_NAME)
            if (!baseDir.exists()) {
                baseDir.mkdirs()
            }
            return File(baseDir, hash).also { repoDir ->
                if (!repoDir.exists()) {
                    repoDir.mkdirs()
                }
            }
        }

        private fun sha256(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
        }

        private companion object {
            private const val GIT_SYNC_REPO_DIR_NAME = "git_sync_repo"
        }
    }
