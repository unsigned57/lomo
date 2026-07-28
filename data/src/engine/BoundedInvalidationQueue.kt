package com.lomo.data.engine

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Bounded, conflated invalidation delivery for native callbacks.
 *
 * Callbacks only [enqueue] and return. Consumer work runs on a dedicated single-thread
 * executor after the callback stack unwinds, so listeners may safely re-enter generated
 * engine methods without nested FFI on the native callback path.
 *
 * Capacity defaults to 256 (stage-1 contract). When the queue is full, newer events collapse
 * into a single overflow slot holding the latest sequence instead of growing unbounded or
 * forging intermediate deltas.
 *
 * A consumer failure is reported through [onFatal] and stops delivery. Letting it end the drain
 * task silently would leave the last published readiness in place forever while the engine moved
 * on, so the write gate would keep admitting writes against a state nobody is observing.
 */
internal class BoundedInvalidationQueue(
    capacity: Int = DEFAULT_CAPACITY,
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor(
            ThreadFactory { runnable ->
                Thread(runnable, DRAIN_THREAD_NAME).apply { isDaemon = true }
            },
        ),
    private val onFatal: (Throwable) -> Unit = {},
    private val deliver: (NativeCoreEvent) -> Unit,
) : AutoCloseable {
    private val queue = ArrayBlockingQueue<NativeCoreEvent>(capacity)
    private val overflow = AtomicReference<NativeCoreEvent?>(null)
    private val stopped = AtomicBoolean(false)
    private val drainScheduled = AtomicBoolean(false)

    fun enqueue(event: NativeCoreEvent) {
        if (stopped.get()) return
        if (!queue.offer(event)) {
            // Conflate under pressure: keep only the newest overflow invalidation.
            overflow.set(event)
        }
        scheduleDrain()
    }

    fun stop() {
        stopped.set(true)
        queue.clear()
        overflow.set(null)
    }

    override fun close() {
        stop()
        executor.shutdown()
        // behavior-contract: silent-result-ok: interrupt drain on close; callers must not block forever
        runCatching { executor.awaitTermination(1, TimeUnit.SECONDS) }
        if (!executor.isTerminated) {
            executor.shutdownNow()
        }
    }

    private fun scheduleDrain() {
        if (stopped.get()) return
        if (!drainScheduled.compareAndSet(false, true)) return
        executor.execute {
            try {
                drainAvailable()
            } finally {
                drainScheduled.set(false)
                if (!stopped.get() && hasPending()) {
                    scheduleDrain()
                }
            }
        }
    }

    private fun drainAvailable() {
        while (!stopped.get()) {
            val next = queue.poll() ?: overflow.getAndSet(null) ?: return
            if (stopped.get()) return
            val failure = runCatching { deliver(next) }.exceptionOrNull() ?: continue
            // Delivery is the only thing that keeps published readiness current; a failing consumer
            // is a fatal invalidation-path failure, not a skippable event.
            stop()
            onFatal(failure)
            return
        }
    }

    private fun hasPending(): Boolean = queue.isNotEmpty() || overflow.get() != null

    companion object {
        const val DEFAULT_CAPACITY: Int = 256
        const val DRAIN_THREAD_NAME: String = "lomo-native-invalidation"
    }
}
