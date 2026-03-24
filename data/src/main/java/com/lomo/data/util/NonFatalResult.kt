package com.lomo.data.util

import kotlinx.coroutines.CancellationException

internal inline fun <T> runNonFatalCatching(block: () -> T): Result<T> =
    runCatching(block).onFailure(::throwIfFatal)

internal fun throwIfFatal(error: Throwable) {
    when (error) {
        is CancellationException -> throw error
        is Error -> throw error
    }
}
