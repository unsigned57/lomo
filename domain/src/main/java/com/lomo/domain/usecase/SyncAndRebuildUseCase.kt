package com.lomo.domain.usecase

import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.GitSyncFailureException
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.model.WebDavSyncFailureException
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.SyncPolicyRepository
import com.lomo.domain.repository.WebDavSyncRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

class SyncAndRebuildUseCase
(
        private val memoRepository: MemoRepository,
        private val gitSyncRepository: GitSyncRepository,
        private val webDavSyncRepository: WebDavSyncRepository,
        private val syncPolicyRepository: SyncPolicyRepository,
    ) {
        suspend operator fun invoke(forceSync: Boolean = false) {
            if (forceSync) {
                val syncFailure = syncFailureOrNull(activeBackend())

                memoRepository.refreshMemos()
                syncFailure?.let { throw it }
                return
            }

            when (activeBackend()) {
                SyncBackendType.GIT -> {
                    val syncOnRefresh = gitSyncRepository.getSyncOnRefreshEnabled().first()
                    val enabled = gitSyncRepository.isGitSyncEnabled().first()
                    if (syncOnRefresh && enabled) {
                        syncFailureOrNull(SyncBackendType.GIT)
                    }
                }

                SyncBackendType.WEBDAV -> {
                    val syncOnRefresh = webDavSyncRepository.getSyncOnRefreshEnabled().first()
                    val enabled = webDavSyncRepository.isWebDavSyncEnabled().first()
                    if (syncOnRefresh && enabled) {
                        syncFailureOrNull(SyncBackendType.WEBDAV)
                    }
                }

                SyncBackendType.NONE -> {
                    Unit
                }
            }

            memoRepository.refreshMemos()
        }

        private suspend fun activeBackend(): SyncBackendType = syncPolicyRepository.observeRemoteSyncBackend().first()

        private suspend fun syncFailureOrNull(backendType: SyncBackendType): Exception? =
            runCatching {
                when (backendType) {
                    SyncBackendType.GIT -> gitResultToException(gitSyncRepository.sync())
                    SyncBackendType.WEBDAV -> webDavResultToException(webDavSyncRepository.sync())
                    SyncBackendType.NONE -> null
                }
            }
                .getOrElse { throwable ->
                    if (throwable is CancellationException) {
                        throw throwable
                    }
                    throwable as? Exception ?: IllegalStateException(throwable.message, throwable)
                }

        private fun gitResultToException(result: GitSyncResult): Exception? =
                when (result) {
                    is GitSyncResult.Success -> null
                    is GitSyncResult.Error -> result.toException(defaultMessage = "Git sync failed")
                    GitSyncResult.NotConfigured ->
                        GitSyncFailureException(
                            code = com.lomo.domain.model.GitSyncErrorCode.NOT_CONFIGURED,
                            message = "Git sync is not configured",
                        )
                    GitSyncResult.DirectPathRequired ->
                        GitSyncFailureException(
                            code = com.lomo.domain.model.GitSyncErrorCode.DIRECT_PATH_REQUIRED,
                            message = "Git sync requires a direct local directory path",
                        )
                    is GitSyncResult.Conflict -> SyncConflictException(result.conflicts)
                }

        private fun webDavResultToException(result: WebDavSyncResult): Exception? =
                when (result) {
                    is WebDavSyncResult.Success -> null
                    is WebDavSyncResult.Error -> result.toException(defaultMessage = "WebDAV sync failed")
                    WebDavSyncResult.NotConfigured ->
                        WebDavSyncFailureException(
                            code = com.lomo.domain.model.WebDavSyncErrorCode.NOT_CONFIGURED,
                            message = "WebDAV sync is not configured",
                        )
                    is WebDavSyncResult.Conflict -> SyncConflictException(result.conflicts)
                }

        private fun GitSyncResult.Error.toException(defaultMessage: String): Exception {
            val cause = exception
            val normalizedMessage = message.ifBlank { defaultMessage }
            if (cause is CancellationException) {
                throw cause
            }
            return GitSyncFailureException(code = code, message = normalizedMessage, cause = cause)
        }

        private fun WebDavSyncResult.Error.toException(defaultMessage: String): Exception {
            val cause = exception
            val normalizedMessage = message.ifBlank { defaultMessage }
            if (cause is CancellationException) {
                throw cause
            }
            return WebDavSyncFailureException(code = code, message = normalizedMessage, cause = cause)
        }

    }

class SyncConflictException(
    val conflicts: SyncConflictSet,
) : Exception("Sync conflict detected: ${conflicts.files.size} file(s)")
