package com.lomo.domain.testing.fakes

import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncReviewResolution
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.model.UnifiedSyncOperation
import com.lomo.domain.model.UnifiedSyncResult
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.repository.SyncInboxRepository
import com.lomo.domain.repository.SyncPolicyRepository
import com.lomo.domain.repository.UnifiedSyncProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeSyncPolicyRepository(
    initialBackend: SyncBackendType = SyncBackendType.NONE,
    private val eventLog: MutableList<String>? = null,
) : SyncPolicyRepository {
    private val backend = MutableStateFlow(initialBackend)

    val setBackendRequests = mutableListOf<SyncBackendType>()
    var applyRemoteSyncPolicyCallCount = 0
        private set
    var ensureCoreSyncActiveCallCount = 0
        private set

    override fun ensureCoreSyncActive() {
        ensureCoreSyncActiveCallCount += 1
    }

    override fun observeRemoteSyncBackend(): Flow<SyncBackendType> = backend.asStateFlow()

    override suspend fun setRemoteSyncBackend(type: SyncBackendType) {
        eventLog?.add("syncPolicy.setRemoteSyncBackend:$type")
        setBackendRequests += type
        backend.value = type
    }

    override suspend fun applyRemoteSyncPolicy() {
        eventLog?.add("syncPolicy.applyRemoteSyncPolicy")
        applyRemoteSyncPolicyCallCount += 1
    }
}

class FakeUnifiedSyncProvider(
    override val backendType: SyncBackendType,
) : UnifiedSyncProvider {
    private val enabled = MutableStateFlow(true)
    private val syncOnRefreshEnabled = MutableStateFlow(true)
    private val state = MutableStateFlow<UnifiedSyncState>(UnifiedSyncState.Idle)

    val syncRequests = mutableListOf<UnifiedSyncOperation>()
    val resolveRequests = mutableListOf<Pair<SyncConflictResolution, SyncConflictSet>>()
    val reviewResolveRequests = mutableListOf<Pair<SyncReviewResolution, SyncReviewSession>>()

    var nextSyncResult: UnifiedSyncResult =
        UnifiedSyncResult.Success(provider = backendType, message = "synced")
    var syncFailure: Exception? = null
    var nextResolveResult: UnifiedSyncResult =
        UnifiedSyncResult.Success(provider = backendType, message = "resolved")

    fun setEnabled(value: Boolean) {
        enabled.value = value
    }

    fun setSyncOnRefreshEnabled(value: Boolean) {
        syncOnRefreshEnabled.value = value
    }

    override fun isEnabled(): Flow<Boolean> = enabled.asStateFlow()

    override fun isSyncOnRefreshEnabled(): Flow<Boolean> = syncOnRefreshEnabled.asStateFlow()

    override fun syncState(): Flow<UnifiedSyncState> = state.asStateFlow()

    override suspend fun sync(operation: UnifiedSyncOperation): UnifiedSyncResult {
        syncRequests += operation
        syncFailure?.let { throw it }
        return nextSyncResult
    }

    override suspend fun resolveConflicts(
        resolution: SyncConflictResolution,
        conflictSet: SyncConflictSet,
    ): UnifiedSyncResult {
        resolveRequests += resolution to conflictSet
        return nextResolveResult
    }

    override suspend fun resolveReview(
        resolution: SyncReviewResolution,
        review: SyncReviewSession,
    ): UnifiedSyncResult {
        reviewResolveRequests += resolution to review
        return nextResolveResult
    }
}

class FakeSyncInboxRepository : SyncInboxRepository {
    private val state = MutableStateFlow<UnifiedSyncState>(UnifiedSyncState.Idle)

    val syncRequests = mutableListOf<UnifiedSyncOperation>()
    val resolveRequests = mutableListOf<Pair<SyncReviewResolution, SyncReviewSession>>()
    var ensureDirectoryStructureCallCount = 0
        private set
    var nextSyncResult: UnifiedSyncResult =
        UnifiedSyncResult.Success(provider = SyncBackendType.INBOX, message = "processed")
    var nextResolveResult: UnifiedSyncResult =
        UnifiedSyncResult.Success(provider = SyncBackendType.INBOX, message = "resolved")

    override fun syncState(): Flow<UnifiedSyncState> = state.asStateFlow()

    override suspend fun ensureDirectoryStructure() {
        ensureDirectoryStructureCallCount += 1
    }

    override suspend fun sync(operation: UnifiedSyncOperation): UnifiedSyncResult {
        syncRequests += operation
        return nextSyncResult
    }

    override suspend fun resolveReview(
        resolution: SyncReviewResolution,
        review: SyncReviewSession,
    ): UnifiedSyncResult {
        resolveRequests += resolution to review
        return nextResolveResult
    }
}
