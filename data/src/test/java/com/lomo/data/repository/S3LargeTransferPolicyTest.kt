package com.lomo.data.repository

import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncReason
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: S3 large-transfer execution policy
 * - Behavior focus: uploads and downloads above the large-transfer threshold should consume the full execution budget so the sync executor does not run multiple memory-heavy transfers concurrently.
 * - Observable outcomes: weighted permit count selected for representative sync actions.
 * - Red phase: Fails before the fix because large uploads/downloads are treated exactly like tiny objects and only consume one execution permit.
 * - Excludes: coroutine scheduling, AWS SDK transport behavior, and memo refresh side effects.
 */
class S3LargeTransferPolicyTest {
    @Test
    fun `small transfer uses a single permit`() {
        val permits =
            permitsForS3Action(
                action =
                    S3SyncAction(
                        path = "lomo/images/small.png",
                        direction = S3SyncDirection.DOWNLOAD,
                        reason = S3SyncReason.REMOTE_ONLY,
                    ),
                localFiles = emptyMap(),
                remoteFiles =
                    mapOf(
                        "lomo/images/small.png" to
                            RemoteS3File(
                                path = "lomo/images/small.png",
                                etag = "etag",
                                lastModified = 10L,
                                size = S3_LARGE_TRANSFER_BYTES - 1,
                            ),
                    ),
                metadataByPath = emptyMap(),
            )

        assertEquals(1, permits)
    }

    @Test
    fun `large upload consumes full execution budget`() {
        val permits =
            permitsForS3Action(
                action =
                    S3SyncAction(
                        path = "lomo/images/large.png",
                        direction = S3SyncDirection.UPLOAD,
                        reason = S3SyncReason.LOCAL_ONLY,
                    ),
                localFiles =
                    mapOf(
                        "lomo/images/large.png" to
                            LocalS3File(
                                path = "lomo/images/large.png",
                                lastModified = 10L,
                                size = S3_LARGE_TRANSFER_BYTES,
                            ),
                    ),
                remoteFiles = emptyMap(),
                metadataByPath = emptyMap(),
            )

        assertEquals(S3_ACTION_CONCURRENCY, permits)
    }
}
