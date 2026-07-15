package com.lomo.data.sync

import com.lomo.data.repository.RemoteSyncPlan

internal object RustSyncPlannerProtocol {
    fun encodeRequest(request: RustSyncPlannerRequest): ByteArray =
        RustSyncRequestEncoder.encode(request)

    fun decodePlan(bytes: ByteArray): RemoteSyncPlan = RustSyncPlanDecoder.decode(bytes)
}
