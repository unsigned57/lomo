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

/**
 * Platform-neutral native engine surface owned by data.
 *
 * Implementations that hold generated BoltFFI handles must also be [AutoCloseable] and release
 * those handles on close. Journal CoreEvent is job poke owned by Rust; Kotlin never subscribes it.
 */
internal interface NativeEnginePort : AutoCloseable {
    fun state(): NativeEngineSnapshot

    fun pollJob(jobId: String): NativeJobStep

    fun submitPlatformResult(
        jobId: String,
        result: com.lomo.nativebridge.PlatformBatchResult,
    ): NativeJobStep

    override fun close()
}
