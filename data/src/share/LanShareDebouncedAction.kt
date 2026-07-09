package com.lomo.data.share

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class LanShareDebouncedAction(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val delayMs: Long,
    private val action: suspend () -> Unit,
) {
    private var pendingJob: Job? = null

    fun trigger() {
        pendingJob?.cancel()
        pendingJob =
            scope.launch(dispatcher) {
                delay(delayMs)
                action()
            }
    }

    fun cancel() {
        pendingJob?.cancel()
        pendingJob = null
    }
}
