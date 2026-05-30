package com.lomo.data.repository

import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.S3SyncErrorCode
import com.lomo.domain.model.S3SyncFailureException
import io.kotest.matchers.shouldBe
import java.io.IOException

/*
 * Behavior Contract:
 * - Unit under test: PendingSyncSideMetadata matching rules and pending restore error classification.
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: pending descriptor validation must reject incomplete required metadata instead of wildcard matching.
 *
 * Scenarios:
 * - Given local descriptor metadata misses required size, when validating local file metadata, then validation fails.
 * - Given remote descriptor metadata misses required etag, when validating remote file metadata, then validation fails.
 * - Given pending restore infrastructure fails, when classifying the failure, then credential invalidation is
 *   reserved for real credential failures and local IO/programming failures remain explicit errors.
 *
 * Observable outcomes:
 * - `matchesLocal` and `matchesRemote` return false for incomplete required metadata.
 * - `toPendingSyncRestoreError` returns the modeled non-invalidation failure category.
 *
 * TDD proof:
 * - RED: tests fail before fix because null fields are treated as wildcard matches.
 * - RED: local IO/programming failure classification maps to `CREDENTIAL_FAILED` before the restore error
 *   model separates operational failures from descriptor invalidation.
 *
 * Excludes:
 * - restore orchestration, remote IO, and repository result mapping.
 */
class PendingSyncSessionRestoreValidationTest : DataFunSpec() {
    init {
        test("matchesLocal rejects descriptor missing required size") {
            val descriptor =
                PendingSyncSideMetadata(
                    locator = "lomo/memo/note.md",
                    contentHash = "hash",
                    lastModified = 10L,
                    size = null,
                    etag = "etag",
                )
            val local = LocalS3File(path = "lomo/memo/note.md", size = 12L, lastModified = 10L)

            descriptor.matchesLocal(local) shouldBe false
        }

        test("matchesRemote rejects descriptor missing required etag") {
            val descriptor =
                PendingSyncSideMetadata(
                    locator = "lomo/memo/note.md",
                    contentHash = "hash",
                    lastModified = 10L,
                    size = 12L,
                    etag = null,
                )

            descriptor.matchesRemote(actualEtag = "etag-live", actualLastModified = 10L, actualSize = 12L) shouldBe false
        }

        test("pending restore error classification keeps local io distinct from credential failure") {
            val error = IOException("Cannot open local pending descriptor").toPendingSyncRestoreError()

            error.category shouldBe PendingSyncRestoreErrorCategory.LOCAL_IO_FAILED
        }

        test("pending restore error classification keeps programming failures distinct from credential failure") {
            val error = IllegalStateException("Missing pending review contract").toPendingSyncRestoreError()

            error.category shouldBe PendingSyncRestoreErrorCategory.CONTRACT_VIOLATION
        }

        test("pending restore error classification maps central budget exhaustion explicitly") {
            val error = RemoteSyncBudgetExceededException(com.lomo.domain.model.SyncBackendType.S3)
                .toPendingSyncRestoreError()

            error.category shouldBe PendingSyncRestoreErrorCategory.BUDGET_EXHAUSTED
        }

        test("pending restore error classification keeps credential failures explicit") {
            val failure =
                S3SyncFailureException(
                    code = S3SyncErrorCode.AUTH_FAILED,
                    message = "Access denied",
                )

            failure.toPendingSyncRestoreError().category shouldBe PendingSyncRestoreErrorCategory.CREDENTIAL_FAILED
        }
    }
}
