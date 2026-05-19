package com.lomo.data.repository

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



import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue

/*
 * Behavior Contract:
 * - Unit under test: S3 remote recent-activity tracker
 * - Behavior focus: foreground candidates, failed paths, and conflict paths should all feed the explicit recent-activity layer that seeds later fast/reconcile scans without waiting for a full shard rotation.
 * - Observable outcomes: persisted remote-index scanPriority, dirtySuspect, and recency fields after tracker updates.
 * - TDD proof: Fails before the fix because recent activity only exists as ad hoc promotion on successful outcomes, leaving failed/manual/conflict paths outside the dedicated recent-activity source described by the manifest-free sync plan.
 * - Excludes: planner action derivation, Room SQL generation, and UI refresh behavior.
 */
class S3RemoteRecentActivityTrackerTest : DataFunSpec() {
    init {
        test("recordForegroundCandidates promotes known paths as recent dirty candidates") { `recordForegroundCandidates promotes known paths as recent dirty candidates`() }

        test("recordRetryCandidates keeps failed and conflict paths hot for targeted follow-up") { `recordRetryCandidates keeps failed and conflict paths hot for targeted follow-up`() }
    }


    private fun `recordForegroundCandidates promotes known paths as recent dirty candidates`() =
        runTest {
            val path = "lomo/memo/manual.md"
            val store =
                InMemoryS3RemoteIndexStore().apply {
                    upsert(
                        listOf(
                            S3RemoteIndexEntry(
                                relativePath = path,
                                remotePath = path,
                                etag = "etag-manual",
                                remoteLastModified = 10L,
                                size = 1L,
                                lastSeenAt = 10L,
                                lastVerifiedAt = 10L,
                                scanBucket = S3_SCAN_BUCKET_MEMO,
                                scanPriority = 100,
                                dirtySuspect = false,
                                missingOnLastScan = false,
                                scanEpoch = 1L,
                            ),
                        ),
                    )
                }
            val tracker = S3RemoteRecentActivityTracker()

            tracker.recordForegroundCandidates(
                remoteIndexStore = store,
                relativePaths = setOf(path),
                now = 400L,
                scanEpoch = 9L,
            )

            val updated = requireNotNull(store.readByRelativePaths(listOf(path)).singleOrNull())
            (updated.dirtySuspect).shouldBeTrue()
            (updated.scanPriority > 100).shouldBeTrue()
            updated.lastSeenAt shouldBe 400L
            updated.scanEpoch shouldBe 9L
        }

    private fun `recordRetryCandidates keeps failed and conflict paths hot for targeted follow-up`() =
        runTest {
            val failedPath = "lomo/memo/failed.md"
            val conflictPath = "lomo/memo/conflict.md"
            val store =
                InMemoryS3RemoteIndexStore().apply {
                    upsert(
                        listOf(
                            existingRecentActivityEntry(failedPath, 120),
                            existingRecentActivityEntry(conflictPath, 110),
                        ),
                    )
                }
            val tracker = S3RemoteRecentActivityTracker()

            tracker.recordRetryCandidates(
                remoteIndexStore = store,
                relativePaths = setOf(failedPath, conflictPath),
                now = 900L,
                scanEpoch = 11L,
            )

            val updatedByPath = store.readByRelativePaths(listOf(failedPath, conflictPath)).associateBy(S3RemoteIndexEntry::relativePath)
            (requireNotNull(updatedByPath[failedPath]).scanPriority > 120).shouldBeTrue()
            (requireNotNull(updatedByPath[conflictPath]).scanPriority > 110).shouldBeTrue()
            (requireNotNull(updatedByPath[failedPath]).dirtySuspect).shouldBeTrue()
            (requireNotNull(updatedByPath[conflictPath]).dirtySuspect).shouldBeTrue()
            requireNotNull(updatedByPath[failedPath]).lastSeenAt shouldBe 900L
            requireNotNull(updatedByPath[conflictPath]).scanEpoch shouldBe 11L
        }

    private fun existingRecentActivityEntry(
        path: String,
        scanPriority: Int,
    ) = S3RemoteIndexEntry(
        relativePath = path,
        remotePath = path,
        etag = "etag-$path",
        remoteLastModified = 10L,
        size = 1L,
        lastSeenAt = 10L,
        lastVerifiedAt = 10L,
        scanBucket = S3_SCAN_BUCKET_MEMO,
        scanPriority = scanPriority,
        dirtySuspect = false,
        missingOnLastScan = false,
        scanEpoch = 1L,
    )
}
