package com.lomo.data.sync

import com.lomo.data.repository.RemoteSyncPlan
import com.lomo.rust.SyncPlannerException
import com.lomo.rust.planSyncEnvelope

internal fun interface RustSyncEnvelopePlanner {
    fun plan(input: ByteArray): ByteArray
}

internal class RustSyncNativePlanningException(
    val reason: String,
    cause: Throwable? = null,
) : IllegalStateException(reason, cause)

internal object UniFfiRustSyncEnvelopePlanner : RustSyncEnvelopePlanner {
    override fun plan(input: ByteArray): ByteArray =
        try {
            planSyncEnvelope(input)
        } catch (error: SyncPlannerException.Rejected) {
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
