package com.lomo.data.repository

import kotlinx.coroutines.CancellationException

internal interface RemoteSyncLifecycleSummaries<TSnapshot, TPlan, TVerified, TFinalized> {
    fun summarizeSnapshot(snapshot: TSnapshot): RemoteSyncSnapshotTelemetry

    fun summarizePlan(plan: TPlan): RemoteSyncActionTelemetry

    fun summarizeVerification(verified: TVerified): RemoteSyncActionTelemetry

    fun summarizeRefresh(finalized: TFinalized): RemoteSyncRefreshTelemetry
}

internal interface RemoteSyncLifecycleStages<
    TSnapshot,
    TPlan,
    TVerified,
    TConflicts,
    TApplied,
    TMetadata,
    TFinalized,
    TResult,
> : RemoteSyncLifecycleSummaries<TSnapshot, TPlan, TVerified, TFinalized> {
    val context: RemoteSyncLifecycleContext

    suspend fun loadSnapshot(session: RemoteSyncLifecycleSession): TSnapshot

    suspend fun plan(
        snapshot: TSnapshot,
        session: RemoteSyncLifecycleSession,
    ): TPlan

    suspend fun verify(
        plan: TPlan,
        session: RemoteSyncLifecycleSession,
    ): TVerified

    suspend fun materializeConflicts(
        verified: TVerified,
        session: RemoteSyncLifecycleSession,
    ): TConflicts

    suspend fun apply(
        verified: TVerified,
        conflicts: TConflicts,
        session: RemoteSyncLifecycleSession,
    ): TApplied

    suspend fun commitMetadata(
        verified: TVerified,
        conflicts: TConflicts,
        applied: TApplied,
        session: RemoteSyncLifecycleSession,
    ): TMetadata

    suspend fun finalize(
        verified: TVerified,
        conflicts: TConflicts,
        applied: TApplied,
        metadata: TMetadata,
        session: RemoteSyncLifecycleSession,
    ): TFinalized

    fun summarizeResult(finalized: TFinalized): RemoteSyncLifecycleResultTelemetry =
        RemoteSyncLifecycleResultTelemetry.Success

    fun mapResult(finalized: TFinalized): TResult

    fun mapError(error: Throwable): TResult

    suspend fun release()
}

internal interface RemoteSyncLifecycleRunner {
    suspend fun <
        TSnapshot,
        TPlan,
        TVerified,
        TConflicts,
        TApplied,
        TMetadata,
        TFinalized,
        TResult,
    > run(
        stages: RemoteSyncLifecycleStages<
            TSnapshot,
            TPlan,
            TVerified,
            TConflicts,
            TApplied,
            TMetadata,
            TFinalized,
            TResult,
        >,
    ): TResult
}

internal class DefaultRemoteSyncLifecycleRunner(
    private val executionOwner: SyncLifecycleExecutionOwner,
) : RemoteSyncLifecycleRunner {
    override suspend fun <
        TSnapshot,
        TPlan,
        TVerified,
        TConflicts,
        TApplied,
        TMetadata,
        TFinalized,
        TResult,
    > run(
        stages: RemoteSyncLifecycleStages<
            TSnapshot,
            TPlan,
            TVerified,
            TConflicts,
            TApplied,
            TMetadata,
            TFinalized,
            TResult,
        >,
    ): TResult {
        val session = executionOwner.begin(stages.context)
        var outcome: RemoteSyncLifecycleOutcome<TResult>? = null
        try {
            val snapshot = stages.loadSnapshot(session)
            session.recordSnapshot(stages.summarizeSnapshot(snapshot))
            val plan = stages.plan(snapshot, session)
            session.recordPlan(stages.summarizePlan(plan))
            val verified = stages.verify(plan, session)
            session.recordVerification(stages.summarizeVerification(verified))
            val conflicts = stages.materializeConflicts(verified, session)
            val applied = stages.apply(verified, conflicts, session)
            val metadata =
                session.measureMetadataCommit {
                    stages.commitMetadata(verified, conflicts, applied, session)
                }
            val finalized =
                session.measureRefresh {
                    stages.finalize(verified, conflicts, applied, metadata, session)
            }
            session.recordRefresh(stages.summarizeRefresh(finalized))
            val resultTelemetry = stages.summarizeResult(finalized)
            outcome =
                RemoteSyncLifecycleOutcome.Success(
                    result = stages.mapResult(finalized),
                    resultTelemetry = resultTelemetry,
                )
        } catch (error: CancellationException) {
            outcome = RemoteSyncLifecycleOutcome.Failure(error)
        } catch (error: Exception) {
            outcome = RemoteSyncLifecycleOutcome.Failure(error)
        } finally {
            try {
                stages.release()
            } catch (releaseError: CancellationException) {
                outcome = RemoteSyncLifecycleOutcome.Failure(releaseError)
            } catch (releaseError: Exception) {
                outcome =
                    when (val current = outcome) {
                        null -> RemoteSyncLifecycleOutcome.Failure(releaseError)
                        is RemoteSyncLifecycleOutcome.Success ->
                            RemoteSyncLifecycleOutcome.Failure(releaseError)
                        is RemoteSyncLifecycleOutcome.Failure -> {
                            current.error.addSuppressed(releaseError)
                            current
                        }
                    }
            }
        }

        return when (val completed = requireNotNull(outcome)) {
            is RemoteSyncLifecycleOutcome.Success -> {
                session.finish(completed.resultTelemetry)
                completed.result
            }
            is RemoteSyncLifecycleOutcome.Failure -> {
                if (completed.error is CancellationException) {
                    session.finish(RemoteSyncLifecycleResultTelemetry.Cancelled)
                    throw completed.error
                }
                session.finish(RemoteSyncLifecycleResultTelemetry.Failure)
                stages.mapError(completed.error)
            }
        }
    }
}



private sealed interface RemoteSyncLifecycleOutcome<out TResult> {
    data class Success<TResult>(
        val result: TResult,
        val resultTelemetry: RemoteSyncLifecycleResultTelemetry,
    ) : RemoteSyncLifecycleOutcome<TResult>

    data class Failure(
        val error: Throwable,
    ) : RemoteSyncLifecycleOutcome<Nothing>
}
