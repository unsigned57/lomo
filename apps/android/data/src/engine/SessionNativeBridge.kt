package com.lomo.data.engine

import com.lomo.nativebridge.PlatformBatchHost
import com.lomo.nativebridge.SessionCivilDate
import com.lomo.nativebridge.SessionCreateMemoRequest
import com.lomo.nativebridge.SessionDeleteMemoRequest
import com.lomo.nativebridge.SessionFireReminderRequest
import com.lomo.nativebridge.SessionMemoView
import com.lomo.nativebridge.SessionPinMemoRequest
import com.lomo.nativebridge.SessionRestoreRequest
import com.lomo.nativebridge.SessionRestoreResult
import com.lomo.nativebridge.SessionRestoreRevisionRequest
import com.lomo.nativebridge.SessionReviewCandidate
import com.lomo.nativebridge.SessionSearchOutcome
import com.lomo.nativebridge.SessionSearchRequest
import com.lomo.nativebridge.SessionStatistics
import com.lomo.nativebridge.SessionStatisticsSnapshot
import com.lomo.nativebridge.SessionTaskItem
import com.lomo.nativebridge.SessionToggleTaskRequest
import com.lomo.nativebridge.SessionUpdateMemoRequest
import com.lomo.nativebridge.StoreMemoCommit
import com.lomo.nativebridge.StoreMemoHistoryPage
import com.lomo.nativebridge.StoreReminderPlan

/**
 * Application-session FFI edge.
 *
 * Production: [BoltFfiNativeEnginePort] / [ManagedEngineSession]. Host tests inject fakes so
 * [com.lomo.data.engine.store.BoltFfiStorePort] mapping is exercised without JNI. Kotlin does not
 * choose dated paths or mint memo identity.
 *
 * Unused host-test ports inherit the fail-closed defaults; production adapters override every
 * method and must not rely on these bodies.
 */
internal interface SessionNativeBridge {
    fun openWorkspaceSession(
        host: PlatformBatchHost,
        timeZone: String,
    ): String = error("workspace session open is not expected")

    fun sessionCreateMemo(request: SessionCreateMemoRequest): StoreMemoCommit =
        error("session create is not expected")

    fun sessionUpdateMemo(request: SessionUpdateMemoRequest): StoreMemoCommit =
        error("session update is not expected")

    fun sessionDeleteMemo(request: SessionDeleteMemoRequest): StoreMemoCommit =
        error("session delete is not expected")

    fun sessionPinMemo(request: SessionPinMemoRequest): StoreMemoCommit =
        error("session pin is not expected")

    fun sessionGetMemo(memoId: String): SessionMemoView? = error("session get is not expected")

    fun sessionSearch(request: SessionSearchRequest): SessionSearchOutcome =
        error("session search is not expected")

    fun sessionListTasks(): List<SessionTaskItem> = error("session tasks are not expected")

    fun sessionToggleTask(request: SessionToggleTaskRequest): StoreMemoCommit =
        error("session toggle task is not expected")

    fun sessionReviewCandidates(
        zone: String,
        date: SessionCivilDate,
    ): List<SessionReviewCandidate> = error("session review is not expected")

    fun sessionCompleteReview(
        zone: String,
        date: SessionCivilDate,
        memoId: String,
    ) {
        error("session complete review is not expected")
    }

    fun sessionStatistics(snapshot: SessionStatisticsSnapshot): SessionStatistics =
        error("session statistics are not expected")

    fun sessionListHistory(
        memoId: String,
        cursor: String?,
        limit: UInt,
    ): StoreMemoHistoryPage = error("session history is not expected")

    fun sessionRestoreMemo(request: SessionRestoreRequest): SessionRestoreResult =
        error("session restore is not expected")

    fun sessionRestoreRevision(request: SessionRestoreRevisionRequest): StoreMemoCommit =
        error("session restore revision is not expected")

    fun sessionPermanentlyDeleteMemo(request: SessionRestoreRequest): SessionRestoreResult =
        error("session permanent delete is not expected")

    fun sessionReminderPlan(nowUtcMs: Long?): StoreReminderPlan =
        error("session reminder plan is not expected")

    fun sessionRecordReminderFired(request: SessionFireReminderRequest): StoreMemoCommit =
        error("session reminder fire is not expected")
}
