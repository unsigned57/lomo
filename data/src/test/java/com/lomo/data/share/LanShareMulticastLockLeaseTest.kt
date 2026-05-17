package com.lomo.data.share


import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

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
class LanShareMulticastLockLeaseTest : DataFunSpec() {
    init {
        test("discovery alone acquires and releases multicast lock") { `discovery alone acquires and releases multicast lock`() }

        test("service and discovery keep multicast lock until both release") { `service and discovery keep multicast lock until both release`() }

        test("duplicate acquire or release for same owner is idempotent") { `duplicate acquire or release for same owner is idempotent`() }
    }


    private fun `discovery alone acquires and releases multicast lock`() {
        val counts = LeaseCallbackCounts()
        val lease = counts.createLease()

        lease.acquire(LanShareMulticastLockOwner.Discovery)
        lease.release(LanShareMulticastLockOwner.Discovery)

        counts.acquireCount shouldBe 1
        counts.releaseCount shouldBe 1
    }

    private fun `service and discovery keep multicast lock until both release`() {
        val counts = LeaseCallbackCounts()
        val lease = counts.createLease()

        lease.acquire(LanShareMulticastLockOwner.Service)
        lease.acquire(LanShareMulticastLockOwner.Discovery)
        lease.release(LanShareMulticastLockOwner.Discovery)
        lease.release(LanShareMulticastLockOwner.Service)

        counts.acquireCount shouldBe 1
        counts.releaseCount shouldBe 1
    }

    private fun `duplicate acquire or release for same owner is idempotent`() {
        val counts = LeaseCallbackCounts()
        val lease = counts.createLease()

        lease.acquire(LanShareMulticastLockOwner.Discovery)
        lease.acquire(LanShareMulticastLockOwner.Discovery)
        lease.release(LanShareMulticastLockOwner.Discovery)
        lease.release(LanShareMulticastLockOwner.Discovery)

        counts.acquireCount shouldBe 1
        counts.releaseCount shouldBe 1
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
