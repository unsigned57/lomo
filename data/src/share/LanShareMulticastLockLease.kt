package com.lomo.data.share

internal enum class LanShareMulticastLockOwner {
    Service,
    Discovery,
}

internal class LanShareMulticastLockLease(
    private val acquireLock: () -> Unit,
    private val releaseLock: () -> Unit,
) {
    private val lock = Any()
    private val activeOwners = mutableSetOf<LanShareMulticastLockOwner>()

    fun acquire(owner: LanShareMulticastLockOwner) {
        val shouldAcquire =
            synchronized(lock) {
                if (!activeOwners.add(owner)) {
                    false
                } else {
                    activeOwners.size == 1
                }
            }
        if (shouldAcquire) {
            acquireLock()
        }
    }

    fun release(owner: LanShareMulticastLockOwner) {
        val shouldRelease =
            synchronized(lock) {
                if (!activeOwners.remove(owner)) {
                    false
                } else {
                    activeOwners.isEmpty()
                }
            }
        if (shouldRelease) {
            releaseLock()
        }
    }
}
