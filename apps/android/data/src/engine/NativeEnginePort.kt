package com.lomo.data.engine

internal sealed interface NativeEngineSnapshot {
    data object AwaitingWorkspaceSelection : NativeEngineSnapshot

    data class Opening(
        val jobId: String,
    ) : NativeEngineSnapshot

    data class Ready(
        val coreRevision: ULong,
        val eventSequence: ULong,
    ) : NativeEngineSnapshot

    data class ReadOnlyRecovery(
        val failure: EngineFailureSnapshot,
    ) : NativeEngineSnapshot

    data object ShuttingDown : NativeEngineSnapshot
}

internal data class EngineFailureSnapshot(
    val category: String,
    val code: String,
    val retryDisposition: String,
    val diagnostic: String,
)

internal data class NativeCoreEvent(
    val coreRevision: ULong,
    val eventSequence: ULong,
)

internal fun interface NativeEngineSubscription {
    fun close()
}

/**
 * Platform-neutral native engine surface owned by data.
 *
 * Implementations that hold generated BoltFFI handles must also be [AutoCloseable] and release
 * those handles on close. Adapters always close the port after the subscription.
 */
internal interface NativeEnginePort : AutoCloseable {
    fun state(): NativeEngineSnapshot

    fun subscribe(listener: (NativeCoreEvent) -> Unit): NativeEngineSubscription

    fun pollJob(jobId: String): NativeJobStep

    fun submitPlatformResult(
        jobId: String,
        result: com.lomo.nativebridge.PlatformBatchResult,
    ): NativeJobStep

    override fun close()
}
