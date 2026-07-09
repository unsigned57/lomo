package com.lomo.app.feature.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.imageLoader
import coil3.request.Disposable
import com.lomo.app.feature.image.FeedImagePreloadSize
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.image.enqueueImagePreloadRequests
import com.lomo.app.feature.image.createImageViewerRequest
import com.lomo.app.feature.memo.MemoCardEntry
import com.lomo.domain.model.Memo
import com.lomo.app.feature.memo.MemoMenuSelection
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

private const val PRELOAD_SIGNATURE_HASH_MULTIPLIER = 31
private const val FEED_MARKDOWN_IMAGE_PRELOAD_RATIO = 16f / 9f
private const val FEED_MARKDOWN_IMAGE_PRELOAD_MIN_WIDTH_PX = 96
private const val FEED_MARKDOWN_IMAGE_PRELOAD_MAX_WIDTH_PX = 1080
private const val FEED_STARTUP_PRELOAD_SIGNATURE_MEMO_COUNT = 6
private val MEMO_CARD_HORIZONTAL_CONTENT_PADDING = 32.dp

internal val MEMO_LIST_HORIZONTAL_PADDING = 16.dp
internal val MEMO_LIST_TOP_PADDING = 16.dp
internal val MEMO_LIST_BOTTOM_PADDING = 88.dp
internal val MEMO_LIST_ITEM_SPACING = 12.dp

internal data class MemoListHorizontalContentPadding(
    val start: Dp,
    val end: Dp,
    val scrollbarOverlaysContent: Boolean,
)

internal fun resolveMemoListHorizontalContentPadding(
    scrollbarEnabled: Boolean,
): MemoListHorizontalContentPadding =
    MemoListHorizontalContentPadding(
        start = MEMO_LIST_HORIZONTAL_PADDING,
        end = MEMO_LIST_HORIZONTAL_PADDING,
        scrollbarOverlaysContent = scrollbarEnabled,
    )

@Composable
internal fun MemoListPreloadEffect(
    loadedWindow: FeedImagePreloadWindow,
    listState: LazyListState,
) {
    val context = LocalContext.current
    val imageLoader = context.imageLoader
    val density = LocalDensity.current
    val fallbackViewportWidthPx = LocalWindowInfo.current.containerSize.width
    val preloadHorizontalInsetPx =
        remember(density) {
            with(density) {
                (MEMO_LIST_HORIZONTAL_PADDING * 2 + MEMO_CARD_HORIZONTAL_CONTENT_PADDING).roundToPx()
            }
        }
    val preloadPlanner = remember { FeedImagePreloadPlanner() }
    val activePreloads = remember { mutableMapOf<String, Disposable>() }
    val startupPreloadMemoSignature =
        remember(loadedWindow) {
            loadedWindow.memos.take(FEED_STARTUP_PRELOAD_SIGNATURE_MEMO_COUNT)
                .fold(0) { acc, memo ->
                    PRELOAD_SIGNATURE_HASH_MULTIPLIER * acc + memo.memo.id.hashCode()
                }
        }

    DisposableEffect(imageLoader, activePreloads, preloadPlanner) {
        onDispose {
            activePreloads.values.forEach(Disposable::dispose)
            activePreloads.clear()
            preloadPlanner.reset()
        }
    }

    LaunchedEffect(
        listState,
        context,
        imageLoader,
        preloadPlanner,
        fallbackViewportWidthPx,
        preloadHorizontalInsetPx,
        startupPreloadMemoSignature,
        loadedWindow,
    ) {
        snapshotFlow {
            FeedImageViewport(
                firstVisible = listState.firstVisibleItemIndex,
                visibleCount = listState.layoutInfo.visibleItemsInfo.size,
                viewportWidthPx = listState.layoutInfo.viewportSize.width,
            )
        }.distinctUntilChanged()
            .collectLatest { viewport ->
                val preloadSize =
                    resolveFeedImagePreloadSize(
                        viewportWidthPx = viewport.viewportWidthPx.takeIf { widthPx -> widthPx > 0 }
                            ?: fallbackViewportWidthPx,
                        horizontalInsetPx = preloadHorizontalInsetPx,
                    )
                val plan =
                    if (viewport.visibleCount == 0) {
                        preloadPlanner.planStartup(
                            loadedWindow = loadedWindow,
                            preloadSize = preloadSize,
                        )
                    } else {
                        preloadPlanner.planViewport(
                            loadedWindow = loadedWindow,
                            firstVisible = viewport.firstVisible,
                            visibleCount = viewport.visibleCount,
                            preloadSize = preloadSize,
                        )
                    }
                applyFeedImagePreloadPlan(
                    context = context,
                    imageLoader = imageLoader,
                    activePreloads = activePreloads,
                    plan = plan,
                )
            }
    }
}

private data class FeedImageViewport(
    val firstVisible: Int,
    val visibleCount: Int,
    val viewportWidthPx: Int,
)

private fun resolveFeedImagePreloadSize(
    viewportWidthPx: Int,
    horizontalInsetPx: Int,
): FeedImagePreloadSize {
    val contentWidthPx =
        (viewportWidthPx - horizontalInsetPx)
            .coerceIn(FEED_MARKDOWN_IMAGE_PRELOAD_MIN_WIDTH_PX, FEED_MARKDOWN_IMAGE_PRELOAD_MAX_WIDTH_PX)
    return FeedImagePreloadSize(
        widthPx = contentWidthPx,
        heightPx = (contentWidthPx / FEED_MARKDOWN_IMAGE_PRELOAD_RATIO).roundToInt().coerceAtLeast(1),
    )
}

private fun applyFeedImagePreloadPlan(
    context: android.content.Context,
    imageLoader: coil3.ImageLoader,
    activePreloads: MutableMap<String, Disposable>,
    plan: FeedImagePreloadPlan,
) {
    plan.cancelUrls.forEach { url ->
        activePreloads.remove(url)?.dispose()
    }
    val specsToStart =
        plan.startRequests
            .filterNot { spec -> activePreloads[spec.url]?.isDisposed == false }
    activePreloads +=
        enqueueImagePreloadRequests(
            context = context,
            imageLoader = imageLoader,
            specs = specsToStart,
        )
}

@Composable
internal fun MemoListItem(
    uiModel: MemoUiModel,
    bottomSpacing: androidx.compose.ui.unit.Dp,
    onTodoClick: (Memo, Int, Boolean) -> Unit,
    onReminderClick: (com.lomo.domain.model.ReminderMarker) -> Unit,
    dateFormat: String,
    timeFormat: String,
    onMemoDoubleClick: (Memo) -> Unit,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    onTagClick: (String) -> Unit,
    onImageClick: (ImageViewerRequest) -> Unit,
    onShowMemoMenu: (MemoMenuSelection) -> Unit,
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    anchoredAfterKey: String? = null,
    isExiting: Boolean = false,
) {
    val stableTodoClick =
        remember(uiModel.memo, onTodoClick) {
            { index: Int, checked: Boolean -> onTodoClick(uiModel.memo, index, checked) }
        }
    val stableImageClick =
        remember(uiModel.imageUrls, onImageClick) {
            { url: String ->
                onImageClick(
                    createImageViewerRequest(
                        imageUrls = uiModel.imageUrls,
                        clickedUrl = url,
                        memoId = uiModel.memo.id,
                    ),
                )
            }
        }

    Box(
        modifier = modifier
            .padding(bottom = bottomSpacing)
    ) {
        key(uiModel.memo.id) {
            MemoCardEntry(
                uiModel = uiModel,
                dateFormat = dateFormat,
                timeFormat = timeFormat,
                onTodoClick = stableTodoClick,
                onTagClick = onTagClick,
                onMemoEdit = onMemoDoubleClick,
                doubleTapEditEnabled = doubleTapEditEnabled,
                freeTextCopyEnabled = freeTextCopyEnabled,
                onImageClick = stableImageClick,
                onShowMenu = onShowMemoMenu,
                onReminderClick = onReminderClick,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
                anchoredAfterKey = anchoredAfterKey,
                isExiting = isExiting,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BoxScope.MemoListPullToRefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
) {
    PullToRefreshDefaults.LoadingIndicator(
        state = state,
        isRefreshing = isRefreshing,
        modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}

internal val MemoUiModel.memoListItemContentBucket: String
    get() = if (imageUrls.isEmpty()) "memo-text" else "memo-media"
