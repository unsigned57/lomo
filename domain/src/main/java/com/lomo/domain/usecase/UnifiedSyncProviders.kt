package com.lomo.domain.usecase

import com.lomo.domain.model.GitSyncFailureException
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.S3SyncErrorCode
import com.lomo.domain.model.S3SyncFailureException
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.S3SyncState
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncEngineState
import com.lomo.domain.model.UnifiedSyncOperation
import com.lomo.domain.model.UnifiedSyncError
import com.lomo.domain.model.UnifiedSyncResult
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.model.UnifiedSyncPhase
import com.lomo.domain.model.WebDavSyncFailureException
import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.model.WebDavSyncState
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.PreferencesRepository
import com.lomo.domain.repository.S3SyncRepository
import com.lomo.domain.repository.SyncInboxRepository
import com.lomo.domain.repository.UnifiedSyncProvider
import com.lomo.domain.repository.WebDavSyncRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class GitUnifiedSyncProvider(
    private val repository: GitSyncRepository,
) : UnifiedSyncProvider {
    override val backendType: SyncBackendType = SyncBackendType.GIT

    override fun isEnabled(): Flow<Boolean> = repository.isGitSyncEnabled()

    override fun isSyncOnRefreshEnabled(): Flow<Boolean> = repository.getSyncOnRefreshEnabled()

    override fun syncState(): Flow<UnifiedSyncState> =
        repository.syncState().map { state -> state.toUnifiedState(backendType) }

    override suspend fun sync(operation: UnifiedSyncOperation): UnifiedSyncResult =
        when (operation) {
            UnifiedSyncOperation.MANUAL_SYNC,
            UnifiedSyncOperation.REFRESH_SYNC,
            ->
                repository
                    .sync()
                    .toUnifiedResult(backendType)

            UnifiedSyncOperation.PROCESS_PENDING_CHANGES ->
                UnifiedSyncResult.Success(
                    provider = backendType,
                    message = "No pending Git-only changes to process",
                )
        }

    override suspend fun resolveConflicts(
        resolution: SyncConflictResolution,
        conflictSet: SyncConflictSet,
    ): UnifiedSyncResult = repository.resolveConflicts(resolution, conflictSet).toUnifiedResult(backendType)
}

class WebDavUnifiedSyncProvider(
    private val repository: WebDavSyncRepository,
) : UnifiedSyncProvider {
    override val backendType: SyncBackendType = SyncBackendType.WEBDAV

    override fun isEnabled(): Flow<Boolean> = repository.isWebDavSyncEnabled()

    override fun isSyncOnRefreshEnabled(): Flow<Boolean> = repository.getSyncOnRefreshEnabled()

    override fun syncState(): Flow<UnifiedSyncState> =
        repository.syncState().map { state -> state.toUnifiedState(backendType) }

    override suspend fun sync(operation: UnifiedSyncOperation): UnifiedSyncResult =
        when (operation) {
            UnifiedSyncOperation.MANUAL_SYNC,
            UnifiedSyncOperation.REFRESH_SYNC,
            ->
                repository
                    .sync()
                    .toUnifiedResult(backendType)

            UnifiedSyncOperation.PROCESS_PENDING_CHANGES ->
                UnifiedSyncResult.Success(
                    provider = backendType,
                    message = "No pending WebDAV-only changes to process",
                )
        }

    override suspend fun resolveConflicts(
        resolution: SyncConflictResolution,
        conflictSet: SyncConflictSet,
    ): UnifiedSyncResult = repository.resolveConflicts(resolution, conflictSet).toUnifiedResult(backendType)
}

class S3UnifiedSyncProvider(
    private val repository: S3SyncRepository,
) : UnifiedSyncProvider {
    override val backendType: SyncBackendType = SyncBackendType.S3

    override fun isEnabled(): Flow<Boolean> = repository.isS3SyncEnabled()

    override fun isSyncOnRefreshEnabled(): Flow<Boolean> = repository.getSyncOnRefreshEnabled()

    override fun syncState(): Flow<UnifiedSyncState> =
        repository.syncState().map { state -> state.toUnifiedState(backendType) }

    override suspend fun sync(operation: UnifiedSyncOperation): UnifiedSyncResult =
        when (operation) {
            UnifiedSyncOperation.MANUAL_SYNC ->
                repository
                    .sync()
                    .toUnifiedResult(backendType)

            UnifiedSyncOperation.REFRESH_SYNC ->
                repository
                    .syncForRefresh()
                    .toUnifiedResult(backendType)

            UnifiedSyncOperation.PROCESS_PENDING_CHANGES ->
                UnifiedSyncResult.Success(
                    provider = backendType,
                    message = "No pending S3-only changes to process",
                )
        }

    override suspend fun resolveConflicts(
        resolution: SyncConflictResolution,
        conflictSet: SyncConflictSet,
    ): UnifiedSyncResult = repository.resolveConflicts(resolution, conflictSet).toUnifiedResult(backendType)
}

class InboxUnifiedSyncProvider(
    private val syncInboxRepository: SyncInboxRepository,
    private val preferencesRepository: PreferencesRepository,
) : UnifiedSyncProvider {
    override val backendType: SyncBackendType = SyncBackendType.INBOX

    override fun isEnabled(): Flow<Boolean> = preferencesRepository.isSyncInboxEnabled()

    override fun isSyncOnRefreshEnabled(): Flow<Boolean> = flowOf(true)

    override fun syncState(): Flow<UnifiedSyncState> = syncInboxRepository.syncState()

    override suspend fun sync(operation: UnifiedSyncOperation): UnifiedSyncResult =
        syncInboxRepository.sync(operation)

    override suspend fun resolveConflicts(
        resolution: SyncConflictResolution,
        conflictSet: SyncConflictSet,
    ): UnifiedSyncResult = syncInboxRepository.resolveConflicts(resolution, conflictSet)
}

internal fun UnifiedSyncResult.toSyncFailureOrNull(): Exception? =
    when (this) {
        is UnifiedSyncResult.Success -> null
        is UnifiedSyncResult.Conflict -> SyncConflictException(conflicts)
        is UnifiedSyncResult.NotConfigured,
        is UnifiedSyncResult.Error,
        -> {
            val unifiedError =
                when (this) {
                    is UnifiedSyncResult.Error -> error
                    is UnifiedSyncResult.NotConfigured -> error
                }
            val errorCause = unifiedError.cause
            if (errorCause is kotlinx.coroutines.CancellationException) {
                throw errorCause
            }
            when (provider) {
                SyncBackendType.GIT ->
                    GitSyncFailureException(
                        code =
                            enumValueOrNull<com.lomo.domain.model.GitSyncErrorCode>(unifiedError.providerCode)
                                ?: com.lomo.domain.model.GitSyncErrorCode.UNKNOWN,
                        message = unifiedError.message,
                        cause = unifiedError.cause,
                    )

                SyncBackendType.WEBDAV ->
                    WebDavSyncFailureException(
                        code =
                            enumValueOrNull<com.lomo.domain.model.WebDavSyncErrorCode>(unifiedError.providerCode)
                                ?: com.lomo.domain.model.WebDavSyncErrorCode.UNKNOWN,
                        message = unifiedError.message,
                        cause = unifiedError.cause,
                    )

                SyncBackendType.S3 ->
                    S3SyncFailureException(
                        code =
                            enumValueOrNull<com.lomo.domain.model.S3SyncErrorCode>(unifiedError.providerCode)
                                ?: com.lomo.domain.model.S3SyncErrorCode.UNKNOWN,
                        message = unifiedError.message,
                        cause = unifiedError.cause,
                    )

                SyncBackendType.INBOX,
                SyncBackendType.NONE,
                ->
                    unifiedError.cause as? Exception
                        ?: IllegalStateException(unifiedError.message, unifiedError.cause)
            }
        }
    }

internal fun UnifiedSyncResult.toResolutionResult(): SyncConflictResolutionResult =
    when (this) {
        is UnifiedSyncResult.Success -> SyncConflictResolutionResult.Resolved
        is UnifiedSyncResult.Conflict -> SyncConflictResolutionResult.Pending(conflicts)
        is UnifiedSyncResult.Error,
        is UnifiedSyncResult.NotConfigured,
        -> throw toSyncFailureOrNull() ?: IllegalStateException(provider.name)
    }

private fun GitSyncResult.toUnifiedResult(
    provider: SyncBackendType,
): UnifiedSyncResult =
    when (this) {
        is GitSyncResult.Success -> UnifiedSyncResult.Success(provider = provider, message = message)
        is GitSyncResult.Error ->
            UnifiedSyncResult.Error(
                provider = provider,
                error =
                    UnifiedSyncError(
                        provider = provider,
                        message = message,
                        cause = exception,
                        providerCode = code.name,
                    ),
            )
        GitSyncResult.NotConfigured -> UnifiedSyncResult.NotConfigured(
            provider = provider,
            error =
                UnifiedSyncError(
                    provider = provider,
                    message = "Git sync is not configured",
                    providerCode = com.lomo.domain.model.GitSyncErrorCode.NOT_CONFIGURED.name,
                ),
        )
        GitSyncResult.DirectPathRequired ->
            UnifiedSyncResult.Error(
                provider = provider,
                error =
                    UnifiedSyncError(
                        provider = provider,
                        message = "Git sync requires a direct local directory path",
                        providerCode = com.lomo.domain.model.GitSyncErrorCode.DIRECT_PATH_REQUIRED.name,
                    ),
            )
        is GitSyncResult.Conflict ->
            UnifiedSyncResult.Conflict(
                provider = provider,
                message = message,
                conflicts = conflicts,
            )
    }

private fun WebDavSyncResult.toUnifiedResult(
    provider: SyncBackendType,
): UnifiedSyncResult =
    when (this) {
        is WebDavSyncResult.Success -> UnifiedSyncResult.Success(provider = provider, message = message)
        is WebDavSyncResult.Error ->
            UnifiedSyncResult.Error(
                provider = provider,
                error =
                    UnifiedSyncError(
                        provider = provider,
                        message = message,
                        cause = exception,
                        providerCode = code.name,
                    ),
            )
        WebDavSyncResult.NotConfigured -> UnifiedSyncResult.NotConfigured(
            provider = provider,
            error =
                UnifiedSyncError(
                    provider = provider,
                    message = "WebDAV sync is not configured",
                    providerCode = com.lomo.domain.model.WebDavSyncErrorCode.NOT_CONFIGURED.name,
                ),
        )
        is WebDavSyncResult.Conflict ->
            UnifiedSyncResult.Conflict(
                provider = provider,
                message = message,
                conflicts = conflicts,
            )
    }

private fun S3SyncResult.toUnifiedResult(
    provider: SyncBackendType,
): UnifiedSyncResult =
    when (this) {
        is S3SyncResult.Success -> UnifiedSyncResult.Success(provider = provider, message = message)
        is S3SyncResult.Error ->
            UnifiedSyncResult.Error(
                provider = provider,
                error =
                    UnifiedSyncError(
                        provider = provider,
                        message = message,
                        cause = exception,
                        providerCode = code.name,
                    ),
            )
        S3SyncResult.NotConfigured -> UnifiedSyncResult.NotConfigured(
            provider = provider,
            error =
                UnifiedSyncError(
                    provider = provider,
                    message = "S3 sync is not configured",
                    providerCode = S3SyncErrorCode.NOT_CONFIGURED.name,
                ),
        )
        is S3SyncResult.Conflict ->
            UnifiedSyncResult.Conflict(
                provider = provider,
                message = message,
                conflicts = conflicts,
            )
    }

fun SyncEngineState.toUnifiedState(provider: SyncBackendType): UnifiedSyncState =
    when (this) {
        SyncEngineState.Idle -> UnifiedSyncState.Idle
        SyncEngineState.Initializing -> UnifiedSyncState.Running(provider, UnifiedSyncPhase.INITIALIZING)
        SyncEngineState.Syncing.Pulling -> UnifiedSyncState.Running(provider, UnifiedSyncPhase.PULLING)
        SyncEngineState.Syncing.Committing -> UnifiedSyncState.Running(provider, UnifiedSyncPhase.COMMITTING)
        SyncEngineState.Syncing.Pushing -> UnifiedSyncState.Running(provider, UnifiedSyncPhase.PUSHING)
        is SyncEngineState.Success -> UnifiedSyncState.Success(provider, timestamp, summary)
        is SyncEngineState.Error ->
            UnifiedSyncState.Error(
                error =
                    UnifiedSyncError(
                        provider = provider,
                        message = message,
                        providerCode = code.name,
                    ),
                timestamp = timestamp,
            )
        SyncEngineState.NotConfigured -> UnifiedSyncState.NotConfigured(provider)
        is SyncEngineState.ConflictDetected -> UnifiedSyncState.ConflictDetected(provider, conflicts)
    }

fun WebDavSyncState.toUnifiedState(provider: SyncBackendType): UnifiedSyncState =
    when (this) {
        WebDavSyncState.Idle -> UnifiedSyncState.Idle
        WebDavSyncState.Initializing -> UnifiedSyncState.Running(provider, UnifiedSyncPhase.INITIALIZING)
        WebDavSyncState.Connecting -> UnifiedSyncState.Running(provider, UnifiedSyncPhase.CONNECTING)
        WebDavSyncState.Listing -> UnifiedSyncState.Running(provider, UnifiedSyncPhase.LISTING)
        WebDavSyncState.Uploading -> UnifiedSyncState.Running(provider, UnifiedSyncPhase.UPLOADING)
        WebDavSyncState.Downloading -> UnifiedSyncState.Running(provider, UnifiedSyncPhase.DOWNLOADING)
        WebDavSyncState.Deleting -> UnifiedSyncState.Running(provider, UnifiedSyncPhase.DELETING)
        is WebDavSyncState.Success -> UnifiedSyncState.Success(provider, timestamp, summary)
        is WebDavSyncState.Error ->
            UnifiedSyncState.Error(
                error =
                    UnifiedSyncError(
                        provider = provider,
                        message = message,
                        providerCode = code.name,
                    ),
                timestamp = timestamp,
            )
        WebDavSyncState.NotConfigured -> UnifiedSyncState.NotConfigured(provider)
        is WebDavSyncState.ConflictDetected -> UnifiedSyncState.ConflictDetected(provider, conflicts)
    }

fun S3SyncState.toUnifiedState(provider: SyncBackendType): UnifiedSyncState =
    when (this) {
        S3SyncState.Idle -> UnifiedSyncState.Idle
        S3SyncState.Initializing -> UnifiedSyncState.Running(provider, UnifiedSyncPhase.INITIALIZING)
        S3SyncState.Connecting -> UnifiedSyncState.Running(provider, UnifiedSyncPhase.CONNECTING)
        S3SyncState.Listing -> UnifiedSyncState.Running(provider, UnifiedSyncPhase.LISTING)
        S3SyncState.Uploading -> UnifiedSyncState.Running(provider, UnifiedSyncPhase.UPLOADING)
        S3SyncState.Downloading -> UnifiedSyncState.Running(provider, UnifiedSyncPhase.DOWNLOADING)
        S3SyncState.Deleting -> UnifiedSyncState.Running(provider, UnifiedSyncPhase.DELETING)
        is S3SyncState.Success -> UnifiedSyncState.Success(provider, timestamp, summary)
        is S3SyncState.Error ->
            UnifiedSyncState.Error(
                error =
                    UnifiedSyncError(
                        provider = provider,
                        message = message,
                        providerCode = code.name,
                    ),
                timestamp = timestamp,
            )
        S3SyncState.NotConfigured -> UnifiedSyncState.NotConfigured(provider)
        is S3SyncState.PreviewingInitialSync ->
            UnifiedSyncState.ConflictDetected(provider, conflicts, isPreview = true)
        is S3SyncState.ConflictDetected -> UnifiedSyncState.ConflictDetected(provider, conflicts)
    }

private inline fun <reified T : Enum<T>> enumValueOrNull(value: String?): T? =
    value?.let { raw -> enumValues<T>().firstOrNull { candidate -> candidate.name == raw } }
