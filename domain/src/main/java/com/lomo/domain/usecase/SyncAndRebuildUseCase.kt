package com.lomo.domain.usecase

import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.SyncPolicyRepository
import com.lomo.domain.repository.WebDavSyncRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

class SyncAndRebuildUseCase
    constructor(
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
            try {
                when (backendType) {
                    SyncBackendType.GIT -> gitResultToException(gitSyncRepository.sync())
                    SyncBackendType.WEBDAV -> webDavResultToException(webDavSyncRepository.sync())
                    SyncBackendType.NONE -> null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }

        private fun gitResultToException(result: GitSyncResult): Exception? =
            when (result) {
                is GitSyncResult.Success -> null
                is GitSyncResult.Error -> result.toException(defaultMessage = "Git sync failed")
                GitSyncResult.NotConfigured -> SyncFailureException("Git sync is not configured")
                GitSyncResult.DirectPathRequired -> SyncFailureException("Git sync requires a direct local directory path")
            }

        private fun webDavResultToException(result: WebDavSyncResult): Exception? =
            when (result) {
                is WebDavSyncResult.Success -> null
                is WebDavSyncResult.Error -> result.toException(defaultMessage = "WebDAV sync failed")
                WebDavSyncResult.NotConfigured -> SyncFailureException("WebDAV sync is not configured")
            }

        private fun GitSyncResult.Error.toException(defaultMessage: String): Exception {
            val cause = exception
            val normalizedMessage = message.ifBlank { defaultMessage }
            if (cause is CancellationException) {
                throw cause
            }
            if (cause is Exception) {
                return SyncFailureException(message = normalizedMessage, cause = cause)
            }
            return SyncFailureException(message = normalizedMessage, cause = cause)
        }

        private fun WebDavSyncResult.Error.toException(defaultMessage: String): Exception {
            val cause = exception
            val normalizedMessage = message.ifBlank { defaultMessage }
            if (cause is CancellationException) {
                throw cause
            }
            if (cause is Exception) {
                return SyncFailureException(message = normalizedMessage, cause = cause)
            }
            return SyncFailureException(message = normalizedMessage, cause = cause)
        }

        private class SyncFailureException(
            message: String,
            cause: Throwable? = null,
        ) : Exception(message, cause)
    }
