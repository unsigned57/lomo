private const val BOLTFFI_FUTURE_POLL_READY: Byte = 0

private class BoltFfiHandleMap<T> {
    private val next = java.util.concurrent.atomic.AtomicLong(1)
    private val values = java.util.concurrent.ConcurrentHashMap<Long, T>()

    fun insert(value: T): Long {
        val handle = next.getAndIncrement()
        values[handle] = value
        return handle
    }

    fun remove(handle: Long): T? = values.remove(handle)
}

private val boltffiContinuationMap =
    BoltFfiHandleMap<kotlinx.coroutines.CancellableContinuation<Byte>>()

private val boltffiCallbackScope =
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())

private object BoltFfiAsync {
    fun resume(handle: Long, pollResult: Byte) {
        val continuation = boltffiContinuationMap.remove(handle) ?: return
        continuation.resumeWith(Result.success(pollResult))
    }
}

internal fun boltffiLaunchCallback(block: suspend () -> Unit) {
    boltffiCallbackScope.launch {
        block()
    }
}

internal suspend fun <T> boltffiCallAsync(
    createFuture: () -> Long,
    poll: (Long, Long) -> Unit,
    complete: (Long) -> T,
    free: (Long) -> Unit,
    cancel: (Long) -> Unit,
): T {
    val rustFuture = createFuture()
    try {
        var pollResult: Byte
        do {
            pollResult = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                val continuationHandle = boltffiContinuationMap.insert(continuation)
                continuation.invokeOnCancellation {
                    if (boltffiContinuationMap.remove(continuationHandle) != null) {
                        cancel(rustFuture)
                    }
                }
                poll(rustFuture, continuationHandle)
            }
        } while (pollResult != BOLTFFI_FUTURE_POLL_READY)
        return complete(rustFuture)
    } finally {
        free(rustFuture)
    }
}
