package com.lomo.data.engine

/**
 * Ordered release of the resources a single owner acquired.
 *
 * Release is not a straight line of calls: a failing step must never skip the next one, or the
 * native engine handle, workspace lock or capability behind it stays held for the rest of the
 * process and the workspace can never be reopened. Every step runs; the first failure is reported
 * and later failures are attached to it as suppressed causes.
 */
internal class ReleaseSequence {
    private var failure: Throwable? = null

    /** Runs one release step, recording its failure instead of propagating it. */
    fun release(step: () -> Unit) {
        runCatching { step() }.exceptionOrNull()?.let(::record)
    }

    /** Records a failure raised outside a [release] step, such as the acquisition that triggered it. */
    fun record(error: Throwable) {
        val first = failure
        if (first == null) {
            failure = error
        } else {
            first.addSuppressed(error)
        }
    }

    /** Throws the aggregated failure when any recorded step failed. */
    fun throwIfFailed() {
        failure?.let { throw it }
    }
}
