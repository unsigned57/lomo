package com.lomo.data.engine

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Read/write lifecycle lease for generated BoltFFI handles.
 *
 * Readers take the shared lock only after the lease is still Open. Close atomically enters
 * Closing, then takes the exclusive write lock so every in-flight reader exits before the
 * close body runs. There is no separate pre-lock reader counter that can deadlock with the
 * write lock.
 */
internal class NativeHandleLease {
    private enum class State {
        Open,
        Closing,
        Closed,
    }

    private val state = AtomicReference(State.Open)
    private val lock = ReentrantReadWriteLock()

    fun <T> withRead(block: () -> T): T {
        ensureOpen()
        return lock.read {
            ensureOpen()
            block()
        }
    }

    /**
     * Transitions Open → Closing, runs [block] under the write lock, then marks Closed.
     *
     * @return true when this call performed close, false when already closing or closed.
     */
    fun closeOnce(block: () -> Unit): Boolean {
        if (!state.compareAndSet(State.Open, State.Closing)) {
            return false
        }
        lock.write {
            try {
                block()
            } finally {
                state.set(State.Closed)
            }
        }
        return true
    }

    fun ensureOpen() {
        when (state.get()) {
            State.Open -> Unit
            State.Closing, State.Closed -> error("Native engine is closed")
        }
    }

    fun isOpen(): Boolean = state.get() == State.Open
}
