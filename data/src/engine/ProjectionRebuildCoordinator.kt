package com.lomo.data.engine

/** Single-flight owner for one adapter's SAF projection rebuild. */
internal class ProjectionRebuildCoordinator {
    private val lock = Any()
    private var active: Flight? = null

    fun run(block: () -> com.lomo.nativebridge.StoreRebuildResult): com.lomo.nativebridge.StoreRebuildResult {
        val (flight, leader) = acquire()
        if (!leader) return await(flight)
        val outcome = runCatching(block)
        flight.outcome = outcome
        flight.completion.countDown()
        release(flight)
        return outcome.getOrThrow()
    }

    private fun acquire(): Pair<Flight, Boolean> =
        synchronized(lock) {
            active?.also { it.waiters += 1 }?.let { it to false }
                ?: Flight().also { active = it } to true
        }

    private fun await(flight: Flight): com.lomo.nativebridge.StoreRebuildResult =
        try {
            flight.completion.await()
            checkNotNull(flight.outcome).getOrThrow()
        } finally {
            release(flight)
        }

    private fun release(flight: Flight) {
        synchronized(lock) {
            flight.waiters -= 1
            if (flight.waiters == 0 && active === flight) active = null
        }
    }

    private class Flight(
        val completion: java.util.concurrent.CountDownLatch = java.util.concurrent.CountDownLatch(1),
        var waiters: Int = 1,
        @Volatile var outcome: Result<com.lomo.nativebridge.StoreRebuildResult>? = null,
    )
}
