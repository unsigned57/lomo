package com.lomo.app.feature.main

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class NewMemoCreationCoordinator<T>(
    private val scope: CoroutineScope,
    private val isListAtAbsoluteTop: () -> Boolean,
    private val scrollListToAbsoluteTop: suspend () -> Unit,
    private val createMemo: (T) -> Unit,
) {
    private var submissionInFlight = false

    fun submit(request: T): Boolean {
        if (submissionInFlight) {
            return false
        }

        submissionInFlight = true
        scope.launch(context = Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try {
                val needsScrollToTop = !isListAtAbsoluteTop()
                if (needsScrollToTop) {
                    scrollListToAbsoluteTop()
                }
                createMemo(request)
            } finally {
                submissionInFlight = false
            }
        }
        return true
    }
}
