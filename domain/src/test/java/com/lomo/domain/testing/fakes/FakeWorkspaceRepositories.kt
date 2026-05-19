package com.lomo.domain.testing.fakes

import com.lomo.domain.repository.WorkspaceStateResolver
import com.lomo.domain.repository.WorkspaceTransitionRepository

class FakeWorkspaceStateResolver(
    private val eventLog: MutableList<String>? = null,
) : WorkspaceStateResolver {
    var rebuildCallCount = 0
        private set
    var rebuildFailure: Exception? = null

    override suspend fun rebuildFromCurrentWorkspace() {
        rebuildFailure?.let { throw it }
        eventLog?.add("workspace.rebuildFromCurrentWorkspace")
        rebuildCallCount += 1
    }
}

class FakeWorkspaceTransitionRepository : WorkspaceTransitionRepository {
    var clearCallCount = 0
        private set

    override suspend fun clearMemoStateAfterWorkspaceTransition() {
        clearCallCount += 1
    }
}
