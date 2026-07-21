package com.lomo.data.engine.store

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.domain.model.Memo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Paging3 source over the production [StorePort] (promoted from P3-09 dark-build).
 *
 * Key is the opaque Rust page-cursor encoding. Does not open SQLite from Kotlin.
 */
class StorePagingSource(
    private val port: StorePort,
    private val query: StoreMemoQuery,
    private val pageSize: Int = 30,
    private val mapItem: (StoreMemoSummary) -> Memo = { it.toDomainMemo(body = it.bodyPreview) },
) : PagingSource<String, Memo>() {
    override suspend fun load(params: LoadParams<String>): LoadResult<String, Memo> =
        runCatching {
            val cursor = params.key?.let { StorePageCursor(encoded = it) }
            val page = port.queryMemos(query, cursor, pageSize.coerceAtLeast(params.loadSize))
            LoadResult.Page(
                data = page.items.map(mapItem),
                prevKey = null,
                nextKey = page.nextCursor?.encoded,
            )
        }.fold(
            onSuccess = { it },
            onFailure = { error ->
                LoadResult.Error(error as? Exception ?: IllegalStateException(error))
            },
        )

    override fun getRefreshKey(state: PagingState<String, Memo>): String? = null
}

private val FALLBACK_DATE_KEY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy_MM_dd")

internal fun StoreMemoSummary.toDomainMemo(body: String): Memo {
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
        geoLocation = null,
    )
}

internal fun StoreMemoSnapshot.toDomainMemo(): Memo = summary.toDomainMemo(body = body)
