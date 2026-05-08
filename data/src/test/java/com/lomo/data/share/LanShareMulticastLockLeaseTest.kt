package com.lomo.data.share

import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: LAN share multicast lock lease policy.
 * - Behavior focus: LAN service advertising and device discovery share one multicast lock without
 *   dropping the lock while either side still needs mDNS traffic.
 * - Observable outcomes: acquire and release callback counts across service/discovery leases.
 * - Red phase: Fails before the fix because discovery does not own a multicast-lock lease, so
 *   discovery-only sessions can run without multicast support.
 * - Excludes: Android WifiManager implementation, live NSD traffic, and HTTP transfer behavior.
 */
class LanShareMulticastLockLeaseTest {
    @Test
    fun `discovery alone acquires and releases multicast lock`() {
        val counts = LeaseCallbackCounts()
        val lease = counts.createLease()

        lease.acquire(LanShareMulticastLockOwner.Discovery)
        lease.release(LanShareMulticastLockOwner.Discovery)

        assertEquals(1, counts.acquireCount)
        assertEquals(1, counts.releaseCount)
    }

    @Test
    fun `service and discovery keep multicast lock until both release`() {
        val counts = LeaseCallbackCounts()
        val lease = counts.createLease()

        lease.acquire(LanShareMulticastLockOwner.Service)
        lease.acquire(LanShareMulticastLockOwner.Discovery)
        lease.release(LanShareMulticastLockOwner.Discovery)
        lease.release(LanShareMulticastLockOwner.Service)

        assertEquals(1, counts.acquireCount)
        assertEquals(1, counts.releaseCount)
    }

    @Test
    fun `duplicate acquire or release for same owner is idempotent`() {
        val counts = LeaseCallbackCounts()
        val lease = counts.createLease()

        lease.acquire(LanShareMulticastLockOwner.Discovery)
        lease.acquire(LanShareMulticastLockOwner.Discovery)
        lease.release(LanShareMulticastLockOwner.Discovery)
        lease.release(LanShareMulticastLockOwner.Discovery)

        assertEquals(1, counts.acquireCount)
        assertEquals(1, counts.releaseCount)
    }

    private class LeaseCallbackCounts {
        var acquireCount = 0
            private set
        var releaseCount = 0
            private set

        fun createLease(): LanShareMulticastLockLease =
            LanShareMulticastLockLease(
                acquireLock = { acquireCount++ },
                releaseLock = { releaseCount++ },
            )
    }
}
