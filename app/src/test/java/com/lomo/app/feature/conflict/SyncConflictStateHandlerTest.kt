package com.lomo.app.feature.conflict

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncReviewItem
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.model.SyncReviewSessionKind
import com.lomo.domain.model.UnifiedSyncError
import com.lomo.domain.model.UnifiedSyncState
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: shared sync conflict state handler.
 * - Owning layer: app conflict orchestration.
 * - Priority tier: P1.
 * - Capability: consume domain UnifiedSyncState conflict/review states from any mounted screen.
 *
 * Scenarios:
 * - Given a provider ConflictDetected state, when the handler consumes it, then the conflict dialog callback is invoked.
 * - Given S3 or WebDAV ReviewRequired states, when the handler consumes them, then the review dialog callback is invoked.
 * - Given a non-conflict provider state, when the handler consumes it, then no dialog callback is invoked.
 * - Given a provider outside the mounted provider set, when the handler consumes it, then no dialog callback is invoked.
 *
 * Observable outcomes:
 * - Recorded dialog callback invocations with the exact conflict set or review session.
 *
 * TDD proof:
 * - Fails before the fix because provider conflict/review state consumption exists only in settings-specific composables and no shared app conflict handler exists for the main screen.
 *
 * Excludes:
 * - Compose dialog rendering, Git provider error-detail dialog presentation, and sync engine state production.
 */
class SyncConflictStateHandlerTest : AppFunSpec() {
    init {
        test("given provider conflict or review state when consumed then matching dialog callback is invoked") {
            val recorder = RecordingConflictCallbacks()
            val s3Conflict = conflictSet(SyncBackendType.S3)
            val webDavReview = reviewSession(SyncBackendType.WEBDAV)
            val s3Review = reviewSession(SyncBackendType.S3)

            consumeSyncConflictState(
                syncState = UnifiedSyncState.ConflictDetected(SyncBackendType.S3, s3Conflict),
                providers = setOf(SyncBackendType.S3, SyncBackendType.WEBDAV),
                onShowConflictDialog = recorder::showConflict,
                onShowReviewDialog = recorder::showReview,
            )
            consumeSyncConflictState(
                syncState = UnifiedSyncState.ReviewRequired(SyncBackendType.WEBDAV, webDavReview),
                providers = setOf(SyncBackendType.S3, SyncBackendType.WEBDAV),
                onShowConflictDialog = recorder::showConflict,
                onShowReviewDialog = recorder::showReview,
            )
            consumeSyncConflictState(
                syncState = UnifiedSyncState.ReviewRequired(SyncBackendType.S3, s3Review),
                providers = setOf(SyncBackendType.S3, SyncBackendType.WEBDAV),
                onShowConflictDialog = recorder::showConflict,
                onShowReviewDialog = recorder::showReview,
            )

            recorder.events shouldBe listOf(
                ConflictDialogEvent.ShowConflict(s3Conflict),
                ConflictDialogEvent.ShowReview(webDavReview),
                ConflictDialogEvent.ShowReview(s3Review),
            )
        }

        test("given non-conflict or unmounted provider state when consumed then no dialog callback is invoked") {
            val recorder = RecordingConflictCallbacks()

            consumeSyncConflictState(
                syncState =
                    UnifiedSyncState.Error(
                        error = UnifiedSyncError(provider = SyncBackendType.GIT, message = "git failed"),
                        timestamp = 123L,
                    ),
                providers = setOf(SyncBackendType.GIT),
                onShowConflictDialog = recorder::showConflict,
                onShowReviewDialog = recorder::showReview,
            )
            consumeSyncConflictState(
                syncState = UnifiedSyncState.ConflictDetected(SyncBackendType.INBOX, conflictSet(SyncBackendType.INBOX)),
                providers = setOf(SyncBackendType.S3),
                onShowConflictDialog = recorder::showConflict,
                onShowReviewDialog = recorder::showReview,
            )

            recorder.events shouldBe emptyList()
        }
    }

    private sealed interface ConflictDialogEvent {
        data class ShowConflict(
            val conflictSet: SyncConflictSet,
        ) : ConflictDialogEvent

        data class ShowReview(
            val review: SyncReviewSession,
        ) : ConflictDialogEvent
    }

    private class RecordingConflictCallbacks {
        val events = mutableListOf<ConflictDialogEvent>()

        fun showConflict(conflictSet: SyncConflictSet) {
            events += ConflictDialogEvent.ShowConflict(conflictSet)
        }

        fun showReview(review: SyncReviewSession) {
            events += ConflictDialogEvent.ShowReview(review)
        }
    }
}

private fun conflictSet(source: SyncBackendType): SyncConflictSet =
    SyncConflictSet(
        source = source,
        files =
            listOf(
                SyncConflictFile(
                    relativePath = "memos/2026_06_10.md",
                    localContent = "local",
                    remoteContent = "remote",
                    isBinary = false,
                ),
            ),
        timestamp = 123L,
    )

private fun reviewSession(source: SyncBackendType): SyncReviewSession =
    SyncReviewSession(
        source = source,
        items =
            listOf(
                SyncReviewItem(
                    relativePath = "memos/2026_06_10.md",
                    localContent = null,
                    incomingContent = "incoming",
                    isBinary = false,
                ),
            ),
        timestamp = 123L,
        kind = SyncReviewSessionKind.INITIAL_IMPORT_PREVIEW,
    )
