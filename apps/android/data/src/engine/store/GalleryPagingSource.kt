package com.lomo.data.engine.store

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.domain.model.EngineCommandFailureException
import com.lomo.domain.model.GalleryMemo
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.CancellationException

/**
 * Rust-cursor-backed PagingSource for the image gallery (`idx_memo_gallery`).
 *
 * Placeholders are intentionally disabled (`COUNT_UNDEFINED`) so neither `COUNT(*)` nor
 * full-body/Markdown hydration runs during gallery scrolling.
 */
class GalleryPagingSource(
    private val port: StorePort,
    private val pageSize: Int = DEFAULT_STORE_PAGING_SIZE,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    registerInvalidation: ((PagingSource<String, GalleryMemo>) -> Unit)? = null,
) : PagingSource<String, GalleryMemo>() {
    init {
        registerInvalidation?.invoke(this)
    }

    override suspend fun load(params: LoadParams<String>): LoadResult<String, GalleryMemo> =
        kotlinx.coroutines.withContext(dispatcherProvider.io) {
            try {
                val page = loadGalleryPage(params)
                LoadResult.Page(
                    data = page.items.map(StoreGalleryItem::toDomainGalleryMemo),
                    prevKey = page.prevCursor?.encoded,
                    nextKey = page.nextCursor?.encoded,
                    itemsBefore = LoadResult.Page.COUNT_UNDEFINED,
                    itemsAfter = LoadResult.Page.COUNT_UNDEFINED,
                )
            } catch (error: EngineCommandFailureException) {
                if (error.failure.code == STALE_CURSOR_ERROR_CODE) {
                    LoadResult.Invalid()
                } else {
                    LoadResult.Error(error)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                LoadResult.Error(error)
            }
        }

    override fun getRefreshKey(state: PagingState<String, GalleryMemo>): String? {
        val anchor = state.anchorPosition ?: return null
        val refreshLoadSize = pageSize.coerceAtLeast(state.config.initialLoadSize)
        val startPosition = (anchor - refreshLoadSize / 2).coerceAtLeast(0)
        if (startPosition == 0) {
            return null
        }
        return state.closestItemToPosition(startPosition)?.id
    }

    private fun loadGalleryPage(params: LoadParams<String>): StoreGalleryPage {
        val loadSize = pageSize.coerceAtLeast(params.loadSize)
        return when (params) {
            is LoadParams.Refresh ->
                port.queryGalleryPage(
                    cursor = null,
                    pageSize = loadSize,
                    startMemoId = params.key,
                    backward = false,
                )

            is LoadParams.Append ->
                port.queryGalleryPage(
                    cursor = StorePageCursor(encoded = requirePagingCursor(params.key, "append")),
                    pageSize = loadSize,
                    startMemoId = null,
                    backward = false,
                )

            is LoadParams.Prepend ->
                port.queryGalleryPage(
                    cursor = StorePageCursor(encoded = requirePagingCursor(params.key, "prepend")),
                    pageSize = loadSize,
                    startMemoId = null,
                    backward = true,
                )
        }
    }

    private companion object {
        const val STALE_CURSOR_ERROR_CODE = "stale_cursor"
    }
}

internal fun StoreGalleryItem.toDomainGalleryMemo(): GalleryMemo = GalleryMemo(
    id = memoId,
    timestamp = createdAtMs,
    updatedAt = updatedAtMs,
    dateKey = sourcePathToDateKey(sourcePath, createdAtMs),
    timePart = timePart,
    imageUrls = imageUrls,
    isPinned = isPinned,
    contentRevision = contentRevision,
    fileFingerprint = fileFingerprint,
)
