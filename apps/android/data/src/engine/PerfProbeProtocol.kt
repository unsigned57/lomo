package com.lomo.data.engine

import org.json.JSONObject

internal const val REBUILD_BATCH_SIZE = 512
internal const val DEFAULT_PAGE_SIZE = 100
internal const val PRODUCTION_PAGE_SIZE = 20
internal const val DEFAULT_PROD_SAMPLES = 30
internal const val HISTORY_PAGE_LIMIT = 50
internal const val DEFAULT_HISTORY_N = 50
internal const val LIST_PAGE_SIZE = 512
internal const val SECRET_LEASE_TTL_MS = 300_000L
internal const val SEARCH_TEXT = "bench"
internal const val FFI_BODY =
    "- 10:00:00\nperf probe ffi roundtrip #bench https://example.com\n- [ ] task\n"

internal val EMPTY_FILTERS =
    com.lomo.nativebridge.StoreMemoFilters(
        tag = null,
        tagSubtree = false,
        dateFromInclusiveMs = null,
        dateUntilExclusiveMs = null,
        hasTodo = null,
        hasAttachment = null,
        hasUrl = null,
        pinnedOnly = false,
        includeTrash = false,
        trashOnly = false,
    )

internal val EMPTY_SORT =
    com.lomo.nativebridge.StoreMemoSort(
        seed = 0L,
        timeZone = "UTC",
        field = com.lomo.nativebridge.StoreMemoSortField.CREATED_AT,
        direction = com.lomo.nativebridge.StoreSortDirection.DESCENDING,
    )

internal fun okPayload(run: String, op: String): JSONObject =
    JSONObject().put("schema", "lomo.perf-probe.v1").put("op", op).put("run", run).put("status", "ok")

internal fun errorPayload(run: String, error: Exception): JSONObject = errorPayload(run, describeError(error))

internal fun errorPayload(run: String, message: String): JSONObject =
    JSONObject().put("schema", "lomo.perf-probe.v1").put("run", run).put("status", "error").put("error", message)

private fun describeError(error: Exception): String = when (error) {
    is com.lomo.nativebridge.EngineError.Failure ->
        "EngineError/${error.failure.category}/${error.failure.code}: ${error.failure.diagnostic}"

    else -> "${error.javaClass.simpleName}: ${error.message}"
}

internal fun elapsedMs(t0: Long): Double = (System.nanoTime() - t0) / NANOS_PER_MILLISECOND

private const val NANOS_PER_MILLISECOND = 1_000_000.0
