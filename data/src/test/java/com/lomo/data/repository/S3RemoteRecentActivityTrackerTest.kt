package com.lomo.data.repository

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: S3 remote recent-activity tracker
 * - Behavior focus: foreground candidates, failed paths, and conflict paths should all feed the explicit recent-activity layer that seeds later fast/reconcile scans without waiting for a full shard rotation.
 * - Observable outcomes: persisted remote-index scanPriority, dirtySuspect, and recency fields after tracker updates.
 * - Red phase: Fails before the fix because recent activity only exists as ad hoc promotion on successful outcomes, leaving failed/manual/conflict paths outside the dedicated recent-activity source described by the manifest-free sync plan.
 * - Excludes: planner action derivation, Room SQL generation, and UI refresh behavior.
 */
class S3RemoteRecentActivityTrackerTest {
    @Test
    fun `recordForegroundCandidates promotes known paths as recent dirty candidates`() =
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
            assertTrue(updated.dirtySuspect)
            assertTrue(updated.scanPriority > 100)
            assertEquals(400L, updated.lastSeenAt)
            assertEquals(9L, updated.scanEpoch)
        }

    @Test
    fun `recordRetryCandidates keeps failed and conflict paths hot for targeted follow-up`() =
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
            assertTrue(requireNotNull(updatedByPath[failedPath]).scanPriority > 120)
            assertTrue(requireNotNull(updatedByPath[conflictPath]).scanPriority > 110)
            assertTrue(requireNotNull(updatedByPath[failedPath]).dirtySuspect)
            assertTrue(requireNotNull(updatedByPath[conflictPath]).dirtySuspect)
            assertEquals(900L, requireNotNull(updatedByPath[failedPath]).lastSeenAt)
            assertEquals(11L, requireNotNull(updatedByPath[conflictPath]).scanEpoch)
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
