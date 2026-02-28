package com.lomo.app.feature.main

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import coil3.imageLoader
import coil3.request.ImageRequest
import com.lomo.app.feature.memo.MemoCardEntry
import com.lomo.domain.model.Memo
import com.lomo.ui.component.menu.MemoMenuState

private const val PRELOAD_LOOKAHEAD_COUNT = 5
private const val PRELOAD_PRIORITIZE_EXTRA = 8
private const val PRELOAD_EVENT_THROTTLE_MS = 150L
private const val PRELOAD_URL_DEDUPE_MS = 12_000L
private const val PRELOAD_TRACKED_URL_LIMIT = 512

@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class,
)
@Composable
internal fun MemoListContent(
    memos: List<MemoUiModel>,
    listState: LazyListState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onVisibleMemoIdsChanged: (Set<String>) -> Unit,
    onTodoClick: (Memo, Int, Boolean) -> Unit,
    dateFormat: String,
    timeFormat: String,
    onMemoDoubleClick: (Memo) -> Unit = {},
    doubleTapEditEnabled: Boolean = true,
    onTagClick: (String) -> Unit,
    onImageClick: (String) -> Unit,
    onShowMemoMenu: (MemoMenuState) -> Unit,
) {
    val pullState = rememberPullToRefreshState()
    val context = LocalContext.current
    val imageLoader = context.imageLoader
    val preloadGate = remember { ImagePreloadGate() }

    LaunchedEffect(listState, memos, preloadGate) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.layoutInfo.visibleItemsInfo.size
        }.collect { (firstVisible, visibleCount) ->
            if (memos.isNotEmpty()) {
                val endExclusive =
                    (firstVisible + visibleCount + PRELOAD_PRIORITIZE_EXTRA)
                        .coerceAtMost(memos.size)
                val prioritizedIds =
                    (firstVisible until endExclusive)
                        .asSequence()
                        .map { index -> memos[index].memo.id }
                        .toSet()
                onVisibleMemoIdsChanged(prioritizedIds)
            } else {
                onVisibleMemoIdsChanged(emptySet())
            }

            val preloadStart = firstVisible + visibleCount
            val preloadEnd = preloadStart + PRELOAD_LOOKAHEAD_COUNT
            val preloadCandidates =
                (preloadStart..preloadEnd)
                    .asSequence()
                    .filter { index -> index in memos.indices }
                    .flatMap { index -> memos[index].imageUrls.asSequence() }
                    .toList()
            val urlsToPreload = preloadGate.selectUrlsToEnqueue(preloadCandidates)
            urlsToPreload.forEach { url ->
                val request =
                    ImageRequest
                        .Builder(context)
                        .data(url)
                        .build()
                imageLoader.enqueue(request)
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        state = pullState,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        if (memos.isEmpty()) {
            com.lomo.ui.component.common.EmptyState(
                icon = Icons.AutoMirrored.Rounded.Notes,
                title = stringResource(R.string.empty_no_memos_title),
                description = stringResource(R.string.empty_no_memos_subtitle),
                modifier = Modifier.fillMaxSize(),
            )
        }

        LazyColumn(
            state = listState,
            contentPadding =
                PaddingValues(
                    top = 16.dp,
                    start = 16.dp,
                    end = 16.dp,
                    bottom =
                        WindowInsets.navigationBars
                            .asPaddingValues()
                            .calculateBottomPadding() + 88.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(
                items = memos,
                key = { it.memo.id },
                contentType = { "memo" },
            ) { uiModel ->
                val deleteAlpha by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (uiModel.isDeleting) 0f else 1f,
                    animationSpec =
                        androidx.compose.animation.core.tween(
                            durationMillis = 300,
                            easing = com.lomo.ui.theme.MotionTokens.EasingStandard,
                        ),
                    label = "DeleteAlpha",
                )
                val stableTodoClick =
                    remember(uiModel.memo, onTodoClick) {
                        { index: Int, checked: Boolean -> onTodoClick(uiModel.memo, index, checked) }
                    }

                MemoCardEntry(
                    uiModel = uiModel,
                    dateFormat = dateFormat,
                    timeFormat = timeFormat,
                    onTodoClick = stableTodoClick,
                    onTagClick = onTagClick,
                    onMemoEdit = onMemoDoubleClick,
                    doubleTapEditEnabled = doubleTapEditEnabled,
                    onImageClick = onImageClick,
                    onShowMenu = onShowMemoMenu,
                    modifier =
                        Modifier
                            .animateItem(
                                fadeInSpec =
                                    keyframes {
                                        durationMillis = 600
                                        0f at 0
                                        0f at 300
                                        1f at 600 using com.lomo.ui.theme.MotionTokens.EasingEmphasizedDecelerate
                                    },
                                fadeOutSpec = null,
                                placementSpec = spring(stiffness = Spring.StiffnessLow),
                            ).fillMaxWidth()
                            .graphicsLayer {
                                this.alpha = deleteAlpha
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            },
                )
            }
        }
    }
}

internal class ImagePreloadGate(
    private val eventThrottleMs: Long = PRELOAD_EVENT_THROTTLE_MS,
    private val dedupeWindowMs: Long = PRELOAD_URL_DEDUPE_MS,
    private val maxTrackedUrls: Int = PRELOAD_TRACKED_URL_LIMIT,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lastEnqueueAtMsByUrl = LinkedHashMap<String, Long>()
    private var lastEventAtMs: Long? = null

    fun selectUrlsToEnqueue(candidates: Iterable<String>): List<String> {
        val now = nowMs()
        if (shouldThrottle(now)) {
            return emptyList()
        }
        evictExpired(now)
        val result = mutableListOf<String>()
        val seenInBatch = HashSet<String>()
        candidates.forEach { rawUrl ->
            val url = rawUrl.trim()
            if (url.isBlank() || !seenInBatch.add(url)) {
                return@forEach
            }
            val lastEnqueueAt = lastEnqueueAtMsByUrl[url]
            if (lastEnqueueAt == null || now - lastEnqueueAt >= dedupeWindowMs) {
                lastEnqueueAtMsByUrl[url] = now
                result += url
            }
        }
        trimTrackingMap()
        return result
    }

    private fun shouldThrottle(now: Long): Boolean {
        val lastEventAt = lastEventAtMs
        if (lastEventAt != null && now - lastEventAt < eventThrottleMs) {
            return true
        }
        lastEventAtMs = now
        return false
    }

    private fun evictExpired(now: Long) {
        if (lastEnqueueAtMsByUrl.isEmpty()) {
            return
        }
        val iterator = lastEnqueueAtMsByUrl.entries.iterator()
        while (iterator.hasNext()) {
            val (_, lastEnqueueAt) = iterator.next()
            if (now - lastEnqueueAt > dedupeWindowMs) {
                iterator.remove()
            }
        }
    }

    private fun trimTrackingMap() {
        if (lastEnqueueAtMsByUrl.size <= maxTrackedUrls) {
            return
        }
        val iterator = lastEnqueueAtMsByUrl.entries.iterator()
        while (lastEnqueueAtMsByUrl.size > maxTrackedUrls && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }
}
