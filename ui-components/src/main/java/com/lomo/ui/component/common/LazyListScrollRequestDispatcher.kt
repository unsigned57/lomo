package com.lomo.ui.component.common

import kotlinx.coroutines.Job

internal fun interface LazyListScrollRequest {
    fun cancel()
}

internal class LazyListScrollRequestDispatcher(
    private val launchRequest: (LazyListScrollTarget) -> LazyListScrollRequest,
) {
    private var activeRequest: LazyListScrollRequest? = null

    fun dispatch(target: LazyListScrollTarget) {
        activeRequest?.cancel()
        activeRequest = launchRequest(target)
    }

    fun cancelActiveRequest() {
        activeRequest?.cancel()
        activeRequest = null
    }
}

internal class CoroutineLazyListScrollRequest(
    private val job: Job,
) : LazyListScrollRequest {
    override fun cancel() {
        job.cancel()
    }
}
