package com.lomo.data.engine.store

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoContentKind
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Paging3 source over the production [StorePort] (promoted from P3-09 dark-build).
 *
 * Refresh keys are memo identities in the current query order. Append/Prepend keys are exclusive
 * publication-coupled cursors. Does not open SQLite from Kotlin.
 */
class StorePagingSource(
    private val port: StorePort,
    private val query: StoreMemoQuery,
    private val pageSize: Int = 30,
    private val mapItem: (StoreMemoSummary) -> Memo = {
        it.toDomainMemo(body = it.bodyPreview, contentKind = MemoContentKind.Preview)
    },
    registerInvalidation: ((PagingSource<*, *>) -> Unit)? = null,
) : PagingSource<String, Memo>() {
    init {
        registerInvalidation?.invoke(this)
    }

    override suspend fun load(params: LoadParams<String>): LoadResult<String, Memo> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val page = loadStorePage(params)
                val itemsBefore = page.itemsBefore.toPagingPlaceholderCount("items_before")
                val itemsAfter = page.itemsAfter.toPagingPlaceholderCount("items_after")
                LoadResult.Page(
                    data = page.items.map(mapItem),
                    prevKey = page.prevCursor?.encoded,
                    nextKey = page.nextCursor?.encoded,
                    itemsBefore =
                        when (params) {
                            is LoadParams.Append -> LoadResult.Page.COUNT_UNDEFINED
                            else -> itemsBefore
                        },
                    itemsAfter =
                        when (params) {
                            is LoadParams.Prepend -> LoadResult.Page.COUNT_UNDEFINED
                            else -> itemsAfter
                        },
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                LoadResult.Error(error)
            }
        }

    override fun getRefreshKey(state: PagingState<String, Memo>): String? {
        val anchor = state.anchorPosition ?: return null
        if (anchor == 0) {
            return null
        }
        return state.closestItemToPosition(anchor)?.id
    }

    private fun loadStorePage(params: LoadParams<String>): StoreMemoPage {
        val loadSize = pageSize.coerceAtLeast(params.loadSize)
        return when (params) {
            is LoadParams.Refresh ->
                port.queryMemos(
                    query = query,
                    cursor = null,
                    pageSize = loadSize,
                    startMemoId = params.key,
                    backward = false,
                )
            is LoadParams.Append ->
                port.queryMemos(
                    query = query,
                    cursor = StorePageCursor(encoded = requirePagingCursor(params.key, "append")),
                    pageSize = loadSize,
                    startMemoId = null,
                    backward = false,
                )
            is LoadParams.Prepend ->
                port.queryMemos(
                    query = query,
                    cursor = StorePageCursor(encoded = requirePagingCursor(params.key, "prepend")),
                    pageSize = loadSize,
                    startMemoId = null,
                    backward = true,
                )
        }
    }
}

private fun requirePagingCursor(key: String?, direction: String): String {
    require(!key.isNullOrBlank()) { "$direction requires a page cursor" }
    return key
}

private fun Long.toPagingPlaceholderCount(field: String): Int {
    require(this in 0..Int.MAX_VALUE.toLong()) {
        "$field $this does not fit paging placeholder count"
    }
    return toInt()
}

private val FALLBACK_DATE_KEY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy_MM_dd")

internal fun StoreMemoSummary.toDomainMemo(
    body: String,
    contentKind: MemoContentKind = MemoContentKind.Preview,
): Memo {
    val dateKey =
        sourcePath
            .substringAfterLast('/')
            .removeSuffix(".md")
            .ifBlank {
                Instant
                    .ofEpochMilli(createdAtMs)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .format(FALLBACK_DATE_KEY)
            }
    val localDate: LocalDate? =
        // behavior-contract: silent-result-ok: invalid epoch remains displayable without date
        runCatching {
            Instant.ofEpochMilli(createdAtMs).atZone(ZoneId.systemDefault()).toLocalDate()
        }.getOrNull()
    return Memo(
        id = memoId,
        timestamp = createdAtMs,
        updatedAt = updatedAtMs,
        content = body,
        rawContent = body,
        dateKey = dateKey,
        localDate = localDate,
        tags = tags,
        imageUrls = imageUrls,
        isPinned = isPinned,
        isDeleted = isTrashed,
        isPending = isPending,
        geoLocation = null,
        reminders = reminders,
        contentRevision = contentRevision,
        fileFingerprint = fileFingerprint,
        contentKind = contentKind,
    )
}

internal fun StoreMemoSnapshot.toDomainMemo(): Memo =
    summary.toDomainMemo(body = body, contentKind = MemoContentKind.Full)
