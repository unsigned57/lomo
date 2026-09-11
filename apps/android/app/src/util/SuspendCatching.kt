package com.lomo.app.util

import kotlinx.coroutines.CancellationException

/**
 * Like [runCatching] but re-throws [CancellationException] so that structured
 * concurrency cancellation is never silently swallowed.
 */
internal inline fun <T> runSuspendCatching(block: () -> T): Result<T> =
    runCatching(block).onFailure { error ->
        if (error is CancellationException) throw error
    }
