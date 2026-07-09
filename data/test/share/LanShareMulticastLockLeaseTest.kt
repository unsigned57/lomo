package com.lomo.data.share

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */



import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: LAN share multicast lock lease policy.
 * - Behavior focus: LAN service advertising and device discovery share one multicast lock without
 *   dropping the lock while either side still needs mDNS traffic.
 * - Observable outcomes: acquire and release callback counts across service/discovery leases.
 * - TDD proof: Fails before the fix because discovery does not own a multicast-lock lease, so
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
