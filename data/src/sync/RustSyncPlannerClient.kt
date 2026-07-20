package com.lomo.data.sync

import com.lomo.data.repository.RemoteSyncPlan
import com.lomo.nativebridge.SyncPlannerError
import com.lomo.nativebridge.planSyncEnvelope

internal fun interface RustSyncEnvelopePlanner {
    fun plan(input: ByteArray): ByteArray
}

internal class RustSyncNativePlanningException(
    val reason: String,
    cause: Throwable? = null,
) : IllegalStateException(reason, cause)

internal object BoltFfiRustSyncEnvelopePlanner : RustSyncEnvelopePlanner {
    override fun plan(input: ByteArray): ByteArray =
        try {
            planSyncEnvelope(input)
        } catch (error: SyncPlannerError.Rejected) {
            throw RustSyncNativePlanningException(error.reason, error)
        }
}

internal class RustSyncPlannerClient(
    private val nativePlanner: RustSyncEnvelopePlanner,
) {
    fun plan(request: RustSyncPlannerRequest): RemoteSyncPlan {
        val input = RustSyncPlannerProtocol.encodeRequest(request)
        val output = nativePlanner.plan(input)
        return RustSyncPlannerProtocol.decodePlan(output)
    }
}
