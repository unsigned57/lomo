package com.lomo.data.engine.store

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoContentKind
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal const val DEFAULT_STORE_PAGING_SIZE = 30

/**
 * Paging3 source over the production [StorePort] (promoted from P3-09 dark-build).
 *
 * Refresh keys are memo identities in the current query order. Append/Prepend keys are exclusive
 * publication-coupled cursors. Does not open SQLite from Kotlin.
 */
class StorePagingSource(
    private val port: StorePort,
    private val query: StoreMemoQuery,
    private val pageSize: Int = DEFAULT_STORE_PAGING_SIZE,
    private val mapItem: (StoreMemoSummary) -> Memo = {
        it.toDomainMemo(body = it.bodyPreview, contentKind = MemoContentKind.Preview)
    },
    private val consumeRefreshIdentity: () -> String? = { null },
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    private val previewPrefetcher: (suspend (List<Memo>) -> List<Memo>)? = null,
    registerInvalidation: ((PagingSource<String, Memo>) -> Unit)? = null,
) : PagingSource<String, Memo>() {
    init {
        registerInvalidation?.invoke(this)
    }

    override suspend fun load(params: LoadParams<String>): LoadResult<String, Memo> =
        kotlinx.coroutines.withContext(dispatcherProvider.io) {
            try {
                val page = loadStorePage(params)
                val itemsBefore = page.itemsBefore.toPagingPlaceholderCount("items_before")
                val itemsAfter = page.itemsAfter.toPagingPlaceholderCount("items_after")
                val rawMemos = page.items.map(mapItem)
                val memos = previewPrefetcher?.invoke(rawMemos) ?: rawMemos
                LoadResult.Page(
                    data = memos,
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LoadResult.Error(error)
            }
        }

    override fun getRefreshKey(state: PagingState<String, Memo>): String? {
        consumeRefreshIdentity()?.takeUnless { identity -> identity.isBlank() }?.let { identity ->
            return identity
        }
        val anchor = state.anchorPosition ?: return null
        // Access hints often come from the bottom visible row. startMemoId is a forward
        // page origin, so using that row directly would replace its visible predecessors
        // with placeholders after every mutation. Center the refresh load on the access.
        val refreshLoadSize = pageSize.coerceAtLeast(state.config.initialLoadSize)
        val startPosition = (anchor - refreshLoadSize / 2).coerceAtLeast(0)
        if (startPosition == 0) {
            return null
        }
        return state.closestItemToPosition(startPosition)?.id
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

internal fun requirePagingCursor(key: String?, direction: String): String {
    require(!key.isNullOrBlank()) { "$direction requires a page cursor" }
    return key
}

private fun Long.toPagingPlaceholderCount(field: String): Int {
    require(this in 0..Int.MAX_VALUE.toLong()) {
        "$field $this does not fit paging placeholder count"
    }
    return toInt()
}

private const val MARKDOWN_FILE_EXTENSION = ".md"
private val FALLBACK_DATE_KEY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy_MM_dd")

internal fun sourcePathToDateKey(sourcePath: String, createdAtMs: Long): String = sourcePath
    .substringAfterLast('/')
    .let { name ->
        if (name.endsWith(MARKDOWN_FILE_EXTENSION, ignoreCase = true)) {
            name.dropLast(MARKDOWN_FILE_EXTENSION.length)
        } else {
            name
        }
    }
    .ifBlank {
        Instant
            .ofEpochMilli(createdAtMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(FALLBACK_DATE_KEY)
    }

internal fun StoreMemoSummary.toDomainMemo(
    body: String,
    contentKind: MemoContentKind = MemoContentKind.Preview,
): Memo {
    val dateKey = sourcePathToDateKey(sourcePath, createdAtMs)
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
        timePart = timePart,
        localDate = localDate,
        tags = tags,
        imageUrls = imageUrls,
        isPinned = isPinned,
        isDeleted = isTrashed,
        isPending = isPending,
        reminders = reminders,
        contentRevision = contentRevision,
        fileFingerprint = fileFingerprint,
        contentKind = contentKind,
        projectedCharCount = charCount,
    )
}

internal fun StoreMemoSnapshot.toDomainMemo(): Memo =
    summary.toDomainMemo(body = body, contentKind = MemoContentKind.Full)
