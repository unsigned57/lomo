package com.lomo.app.feature.main

import com.lomo.app.feature.image.FeedImagePreloadSize
import com.lomo.app.feature.image.ImagePreloadSpec
import kotlinx.collections.immutable.ImmutableList

internal data class FeedImagePreloadPlan(
    val startRequests: List<ImagePreloadSpec>,
    val cancelUrls: Set<String>,
)

internal data class FeedImagePreloadWindow(
    val placeholdersBefore: Int,
    val memos: ImmutableList<MemoUiModel>,
) {
    init {
        require(placeholdersBefore >= 0) { "placeholdersBefore must be non-negative." }
    }

    fun memoAtAbsoluteIndex(index: Int): MemoUiModel? =
        memos.getOrNull(index - placeholdersBefore)
}

internal class FeedImagePreloadPlanner(
    private val lookaheadMemoCount: Int = DEFAULT_LOOKAHEAD_MEMO_COUNT,
    private val startupMemoCount: Int = DEFAULT_STARTUP_MEMO_COUNT,
    private val maxActiveRequests: Int = DEFAULT_MAX_ACTIVE_REQUESTS,
) {
    private var activeRequestsByUrl: Map<String, FeedImagePreloadSize> = emptyMap()

    init {
        require(lookaheadMemoCount >= 0) { "lookaheadMemoCount must be non-negative." }
        require(startupMemoCount >= 0) { "startupMemoCount must be non-negative." }
        require(maxActiveRequests > 0) { "maxActiveRequests must be positive." }
    }

    fun planStartup(
        loadedWindow: FeedImagePreloadWindow,
        preloadSize: FeedImagePreloadSize,
    ): FeedImagePreloadPlan =
        planForMemos(
            memos = loadedWindow.memos.asSequence().take(startupMemoCount),
            preloadSize = preloadSize,
        )

    fun planViewport(
        loadedWindow: FeedImagePreloadWindow,
        firstVisible: Int,
        visibleCount: Int,
        preloadSize: FeedImagePreloadSize,
    ): FeedImagePreloadPlan {
        val safeFirstVisible = firstVisible.coerceAtLeast(0)
        val safeVisibleCount = visibleCount.coerceAtLeast(0)
        val visibleEndExclusive = safeFirstVisible + safeVisibleCount
        val visibleIndices = safeFirstVisible until visibleEndExclusive
        val lookaheadIndices = visibleEndExclusive until visibleEndExclusive + lookaheadMemoCount
        return planForIndices(
            loadedWindow = loadedWindow,
            indices = visibleIndices + lookaheadIndices,
            preloadSize = preloadSize,
        )
    }

    fun reset() {
        activeRequestsByUrl = emptyMap()
    }

    private fun planForIndices(
        loadedWindow: FeedImagePreloadWindow,
        indices: Iterable<Int>,
        preloadSize: FeedImagePreloadSize,
    ): FeedImagePreloadPlan =
        planForMemos(
            memos =
                indices
                    .asSequence()
                    .mapNotNull(loadedWindow::memoAtAbsoluteIndex),
            preloadSize = preloadSize,
        )

    private fun planForMemos(
        memos: Sequence<MemoUiModel>,
        preloadSize: FeedImagePreloadSize,
    ): FeedImagePreloadPlan {
        val desiredUrls = LinkedHashSet<String>(maxActiveRequests)
        for (memo in memos) {
            for (rawUrl in memo.imageUrls) {
                val url = rawUrl.trim()
                if (url.isEmpty()) {
                    continue
                }
                desiredUrls += url
                if (desiredUrls.size >= maxActiveRequests) {
                    return buildPlan(
                        desiredUrls = desiredUrls,
                        preloadSize = preloadSize,
                    )
                }
            }
        }
        return buildPlan(
            desiredUrls = desiredUrls,
            preloadSize = preloadSize,
        )
    }

    private fun buildPlan(
        desiredUrls: LinkedHashSet<String>,
        preloadSize: FeedImagePreloadSize,
    ): FeedImagePreloadPlan {
        val desiredRequestsByUrl = desiredUrls.associateWith { preloadSize }
        val desiredUrlSet = desiredRequestsByUrl.keys
        val urlsLeavingWindow = activeRequestsByUrl.keys - desiredUrlSet
        val urlsWithStaleSize =
            desiredRequestsByUrl
                .filter { (url, size) -> activeRequestsByUrl[url]?.let { activeSize -> activeSize != size } == true }
                .keys
        val requestsToStart =
            desiredUrls
                .asSequence()
                .filter { url -> activeRequestsByUrl[url] != preloadSize }
                .map { url ->
                    ImagePreloadSpec(
                        url = url,
                        size = preloadSize,
                    )
                }.toList()
        activeRequestsByUrl = desiredRequestsByUrl
        return FeedImagePreloadPlan(
            startRequests = requestsToStart,
            cancelUrls = urlsLeavingWindow + urlsWithStaleSize,
        )
    }

    private companion object {
        private const val DEFAULT_LOOKAHEAD_MEMO_COUNT = 5
        private const val DEFAULT_STARTUP_MEMO_COUNT = 6
        private const val DEFAULT_MAX_ACTIVE_REQUESTS = 12
    }
}
