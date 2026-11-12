package com.lomo.data.engine

import com.lomo.domain.model.MemoOperationId
import java.util.UUID

/** Owns client retry tokens for one probe invocation before any memo command is planned. */
internal class ProbeWriteInvocation private constructor(private val prefix: String) {
    fun operationId(index: Int): String {
        require(index >= 0) { "Probe operation index must be nonnegative" }
        return MemoOperationId("$prefix-$index").value
    }

    companion object {
        fun newInvocation(run: String): ProbeWriteInvocation =
            ProbeWriteInvocation("probe-hseed-$run-${UUID.randomUUID()}")
    }
}
