package com.lomo.domain.testing.fakes

import com.lomo.domain.repository.WorkspaceStateResolver

class FakeWorkspaceStateResolver(
    private val eventLog: MutableList<String>? = null,
) : WorkspaceStateResolver {
    var rebuildCallCount = 0
        private set
    /** Permanent failure for every rebuild after [remainingRebuildFailures] is exhausted. */
    var rebuildFailure: Exception? = null
    /** Fail the next N rebuilds, then succeed (unless [rebuildFailure] is also set). */
    var remainingRebuildFailures: Int = 0
    /** Exception thrown while [remainingRebuildFailures] remains positive. */
    var remainingRebuildFailure: Exception? = null

    override suspend fun rebuildFromCurrentWorkspace() {
        if (remainingRebuildFailures > 0) {
            remainingRebuildFailures -= 1
            throw remainingRebuildFailure ?: IllegalStateException("rebuild failed")
        }
        rebuildFailure?.let { throw it }
        eventLog?.add("workspace.rebuildFromCurrentWorkspace")
        rebuildCallCount += 1
    }
}
