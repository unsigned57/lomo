package com.lomo.data.local

import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong

internal object MemoFtsTelemetry {
    private val queryCount = AtomicLong(0)
    private val emptyResultCount = AtomicLong(0)
    private val autoRepairCount = AtomicLong(0)
    private val autoRepairTotalDurationMs = AtomicLong(0)

    fun recordSearchResult(
        durationMs: Long,
        isEmptyResult: Boolean,
    ) {
        queryCount.incrementAndGet()
        if (isEmptyResult) {
            emptyResultCount.incrementAndGet()
        }
        Timber.tag(TAG).d(
            "FTS query duration=%dms empty=%s totals(query=%d, empty=%d)",
            durationMs,
            isEmptyResult,
            queryCount.get(),
            emptyResultCount.get(),
        )
    }

    fun recordAutoRepair(durationMs: Long) {
        autoRepairCount.incrementAndGet()
        autoRepairTotalDurationMs.addAndGet(durationMs)
        Timber.tag(TAG).w(
            "FTS auto-repair completed in %dms (count=%d, totalDurationMs=%d)",
            durationMs,
            autoRepairCount.get(),
            autoRepairTotalDurationMs.get(),
        )
    }

    private const val TAG = "MemoFtsTelemetry"
}
