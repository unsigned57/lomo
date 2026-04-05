package com.lomo.app.feature.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.imageLoader
import coil3.request.ImageRequest
import com.lomo.app.R
import com.lomo.app.feature.common.DeleteAnimationVisualPolicy
import com.lomo.app.feature.common.resolveDeleteAnimationVisualPolicy
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.image.createImageViewerRequest
import com.lomo.app.feature.memo.MemoCardEntry
import com.lomo.domain.model.Memo
import com.lomo.ui.component.menu.MemoMenuState
import com.lomo.ui.theme.MotionTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

private const val PRELOAD_LOOKAHEAD_COUNT = 5
private const val STARTUP_PRELOAD_MEMO_COUNT = 6
private const val PRELOAD_EVENT_THROTTLE_MS = 150L
private const val PRELOAD_URL_DEDUPE_MS = 12_000L
private const val PRELOAD_TRACKED_URL_LIMIT = 512

private val MEMO_LIST_HORIZONTAL_PADDING = 16.dp
private val MEMO_LIST_TOP_PADDING = 16.dp
private val MEMO_LIST_BOTTOM_PADDING = 88.dp
private val MEMO_LIST_ITEM_SPACING = 12.dp
private const val MEMO_ITEM_HIDDEN_ALPHA = 0f
private const val MEMO_ITEM_VISIBLE_ALPHA = 1f
private const val MEMO_ITEM_ALPHA_THRESHOLD = 0.999f
private const val MEMO_DELETE_ANIMATION_DURATION_MILLIS = 300
private const val MEMO_DELETE_FADE_DELAY_MILLIS = 300
private const val MEMO_COLLAPSE_ANIMATION_DURATION_MILLIS = 220

@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
internal fun MemoListContent(
    memos: List<MemoUiModel>,
    deletingMemoIds: kotlinx.coroutines.flow.StateFlow<Set<String>>,
    collapsingMemoIds: kotlinx.coroutines.flow.StateFlow<Set<String>>,
    newMemoInsertAnimationState: NewMemoInsertAnimationState = NewMemoInsertAnimationState(),
    onNewMemoSpacePrepared: (String) -> Unit = {},
    onNewMemoRevealConsumed: (String) -> Unit = {},
    listState: LazyListState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onTodoClick: (Memo, Int, Boolean) -> Unit,
    dateFormat: String,
    timeFormat: String,
    onMemoDoubleClick: (Memo) -> Unit = {},
    doubleTapEditEnabled: Boolean = true,
    freeTextCopyEnabled: Boolean = false,
    onTagClick: (String) -> Unit,
    onImageClick: (ImageViewerRequest) -> Unit,
    onShowMemoMenu: (MemoMenuState) -> Unit,
) {
    val pullState = rememberPullToRefreshState()
    val deletingIds by deletingMemoIds.collectAsStateWithLifecycle()
    val collapsingIds by collapsingMemoIds.collectAsStateWithLifecycle()

    MemoListPreloadEffect(
        memos = memos,
        listState = listState,
    )

    MemoListBody(
        memos = memos,
        deletingIds = deletingIds,
        collapsingIds = collapsingIds,
        newMemoInsertAnimationState = newMemoInsertAnimationState,
        onNewMemoSpacePrepared = onNewMemoSpacePrepared,
        onNewMemoRevealConsumed = onNewMemoRevealConsumed,
        listState = listState,
        pullState = pullState,
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        onTodoClick = onTodoClick,
        dateFormat = dateFormat,
        timeFormat = timeFormat,
        onMemoDoubleClick = onMemoDoubleClick,
        doubleTapEditEnabled = doubleTapEditEnabled,
        freeTextCopyEnabled = freeTextCopyEnabled,
        onTagClick = onTagClick,
        onImageClick = onImageClick,
        onShowMemoMenu = onShowMemoMenu,
    )
}

@Composable
private fun MemoListPreloadEffect(
    memos: List<MemoUiModel>,
    listState: LazyListState,
) {
    val context = LocalContext.current
    val imageLoader = context.imageLoader
    val preloadGate = remember { ImagePreloadGate() }
    val latestMemos by rememberUpdatedState(memos)

    LaunchedEffect(memos, context, imageLoader, preloadGate) {
        val urlsToPreload =
            withContext(Dispatchers.Default) {
                preloadGate.selectUrlsToEnqueue(
                    buildStartupImagePreloadCandidates(
                        memos = memos,
                        startupMemoCount = STARTUP_PRELOAD_MEMO_COUNT,
                    ),
                )
            }
        enqueueImagePreloadRequests(
            context = context,
            imageLoader = imageLoader,
            urls = urlsToPreload,
        )
    }

    LaunchedEffect(listState, context, imageLoader, preloadGate) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.layoutInfo.visibleItemsInfo.size
        }.distinctUntilChanged()
            .collectLatest { (firstVisible, visibleCount) ->
                val urlsToPreload =
                    withContext(Dispatchers.Default) {
                        val preloadCandidates =
                            buildVisibleAndLookaheadImagePreloadCandidates(
                                memos = latestMemos,
                                firstVisible = firstVisible,
                                visibleCount = visibleCount,
                                lookaheadCount = PRELOAD_LOOKAHEAD_COUNT,
                            )
                        preloadGate.selectUrlsToEnqueue(preloadCandidates)
                    }
                enqueueImagePreloadRequests(
                    context = context,
                    imageLoader = imageLoader,
                    urls = urlsToPreload,
                )
            }
    }
}

internal fun buildStartupImagePreloadCandidates(
    memos: List<MemoUiModel>,
    startupMemoCount: Int,
): List<String> =
    memos
        .asSequence()
        .take(startupMemoCount.coerceAtLeast(0))
        .flatMap { memo -> memo.imageUrls.asSequence() }
        .toList()

private fun buildVisibleAndLookaheadImagePreloadCandidates(
    memos: List<MemoUiModel>,
    firstVisible: Int,
    visibleCount: Int,
    lookaheadCount: Int,
): List<String> {
    val visibleEndExclusive = firstVisible + visibleCount
    val lookaheadStart = visibleEndExclusive
    val preloadEndExclusive = lookaheadStart + lookaheadCount
    return (firstVisible until visibleEndExclusive)
        .asSequence()
        .plus((lookaheadStart until preloadEndExclusive).asSequence())
        .filter { index -> index in memos.indices }
        .flatMap { index -> memos[index].imageUrls.asSequence() }
        .toList()
}

private fun enqueueImagePreloadRequests(
    context: android.content.Context,
    imageLoader: coil3.ImageLoader,
    urls: List<String>,
) {
    urls.forEach { url ->
        val request =
            ImageRequest
                .Builder(context)
                .data(url)
                .build()
        imageLoader.enqueue(request)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MemoListBody(
    memos: List<MemoUiModel>,
    deletingIds: Set<String>,
    collapsingIds: Set<String>,
    newMemoInsertAnimationState: NewMemoInsertAnimationState,
    onNewMemoSpacePrepared: (String) -> Unit,
    onNewMemoRevealConsumed: (String) -> Unit,
    listState: LazyListState,
    pullState: PullToRefreshState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onTodoClick: (Memo, Int, Boolean) -> Unit,
    dateFormat: String,
    timeFormat: String,
    onMemoDoubleClick: (Memo) -> Unit,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    onTagClick: (String) -> Unit,
    onImageClick: (ImageViewerRequest) -> Unit,
    onShowMemoMenu: (MemoMenuState) -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        state = pullState,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
        indicator = {
            MemoListPullToRefreshIndicator(
                state = pullState,
                isRefreshing = isRefreshing,
            )
        },
    ) {
        if (memos.isEmpty()) {
            com.lomo.ui.component.common.EmptyState(
                icon = Icons.AutoMirrored.Rounded.Notes,
                title = stringResource(R.string.empty_no_memos_title),
                description = stringResource(R.string.empty_no_memos_subtitle),
                modifier = Modifier.fillMaxSize(),
            )
            return@PullToRefreshBox
        }

        MemoListColumn(
            memos = memos,
            deletingIds = deletingIds,
            collapsingIds = collapsingIds,
            newMemoInsertAnimationState = newMemoInsertAnimationState,
            onNewMemoSpacePrepared = onNewMemoSpacePrepared,
            onNewMemoRevealConsumed = onNewMemoRevealConsumed,
            listState = listState,
            onTodoClick = onTodoClick,
            dateFormat = dateFormat,
            timeFormat = timeFormat,
            onMemoDoubleClick = onMemoDoubleClick,
            doubleTapEditEnabled = doubleTapEditEnabled,
            freeTextCopyEnabled = freeTextCopyEnabled,
            onTagClick = onTagClick,
            onImageClick = onImageClick,
            onShowMemoMenu = onShowMemoMenu,
        )
    }
}

@Composable
private fun MemoListColumn(
    memos: List<MemoUiModel>,
    deletingIds: Set<String>,
    collapsingIds: Set<String>,
    newMemoInsertAnimationState: NewMemoInsertAnimationState,
    onNewMemoSpacePrepared: (String) -> Unit,
    onNewMemoRevealConsumed: (String) -> Unit,
    listState: LazyListState,
    onTodoClick: (Memo, Int, Boolean) -> Unit,
    dateFormat: String,
    timeFormat: String,
    onMemoDoubleClick: (Memo) -> Unit,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    onTagClick: (String) -> Unit,
    onImageClick: (ImageViewerRequest) -> Unit,
    onShowMemoMenu: (MemoMenuState) -> Unit,
) {
    LazyColumn(
        state = listState,
        contentPadding =
            PaddingValues(
                top = MEMO_LIST_TOP_PADDING,
                start = MEMO_LIST_HORIZONTAL_PADDING,
                end = MEMO_LIST_HORIZONTAL_PADDING,
                bottom =
                    WindowInsets.navigationBars
                        .asPaddingValues()
                        .calculateBottomPadding() + MEMO_LIST_BOTTOM_PADDING,
            ),
        verticalArrangement = Arrangement.Top,
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(
            items = memos,
            key = { _, item -> item.memo.id },
            contentType = { _, _ -> "memo" },
        ) { index, uiModel ->
            val deleteAnimationPolicy =
                resolveDeleteAnimationVisualPolicy(
                    isDeleting = uiModel.memo.id in deletingIds,
                )
            val shouldHoldNewMemoHidden =
                index == 0 &&
                    newMemoInsertAnimationState.awaitingInsertedTopMemo &&
                    uiModel.memo.id != newMemoInsertAnimationState.previousTopMemoId
            val shouldAnimateNewMemoSpace =
                newMemoInsertAnimationState.blankSpaceMemoId == uiModel.memo.id
            val shouldHoldGapReadyMemoHidden =
                newMemoInsertAnimationState.gapReadyMemoId == uiModel.memo.id
            val shouldAnimateNewMemoReveal =
                newMemoInsertAnimationState.pendingRevealMemoId == uiModel.memo.id
            MemoListItem(
                uiModel = uiModel,
                isDeleting = uiModel.memo.id in deletingIds,
                isCollapsing = uiModel.memo.id in collapsingIds,
                shouldHoldNewMemoHidden = shouldHoldNewMemoHidden,
                shouldHoldGapReadyMemoHidden = shouldHoldGapReadyMemoHidden,
                shouldAnimateNewMemoSpace = shouldAnimateNewMemoSpace,
                shouldAnimateNewMemoReveal = shouldAnimateNewMemoReveal,
                bottomSpacing = if (index == memos.lastIndex) 0.dp else MEMO_LIST_ITEM_SPACING,
                deleteAnimationPolicy = deleteAnimationPolicy,
                onTodoClick = onTodoClick,
                dateFormat = dateFormat,
                timeFormat = timeFormat,
                onMemoDoubleClick = onMemoDoubleClick,
                doubleTapEditEnabled = doubleTapEditEnabled,
                freeTextCopyEnabled = freeTextCopyEnabled,
                onTagClick = onTagClick,
                onImageClick = onImageClick,
                onShowMemoMenu = onShowMemoMenu,
                onNewMemoSpacePrepared = onNewMemoSpacePrepared,
                onNewMemoRevealConsumed = onNewMemoRevealConsumed,
                modifier =
                    Modifier
                        .memoListPlacementAnimation(
                            lazyItemScope = this,
                            deleteAnimationPolicy = deleteAnimationPolicy,
                            newMemoInsertAnimationState = newMemoInsertAnimationState,
                        )
                        .fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MemoListItem(
    uiModel: MemoUiModel,
    isDeleting: Boolean,
    isCollapsing: Boolean,
    shouldHoldNewMemoHidden: Boolean,
    shouldHoldGapReadyMemoHidden: Boolean,
    shouldAnimateNewMemoSpace: Boolean,
    shouldAnimateNewMemoReveal: Boolean,
    bottomSpacing: androidx.compose.ui.unit.Dp,
    deleteAnimationPolicy: DeleteAnimationVisualPolicy,
    onTodoClick: (Memo, Int, Boolean) -> Unit,
    dateFormat: String,
    timeFormat: String,
    onMemoDoubleClick: (Memo) -> Unit,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    onTagClick: (String) -> Unit,
    onImageClick: (ImageViewerRequest) -> Unit,
    onShowMemoMenu: (MemoMenuState) -> Unit,
    onNewMemoSpacePrepared: (String) -> Unit,
    onNewMemoRevealConsumed: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val deleteAlpha by animateFloatAsState(
        targetValue =
            if (isDeleting) {
                MEMO_ITEM_HIDDEN_ALPHA
            } else {
                MEMO_ITEM_VISIBLE_ALPHA
            },
        animationSpec =
            androidx.compose.animation.core.tween(
                durationMillis = MEMO_DELETE_ANIMATION_DURATION_MILLIS,
                easing = com.lomo.ui.theme.MotionTokens.EasingStandard,
            ),
        label = "DeleteAlpha",
    )
    val animatedBottomSpacing by animateDpAsState(
        targetValue = if (isCollapsing) 0.dp else bottomSpacing,
        animationSpec =
            androidx.compose.animation.core.tween(
                durationMillis = MEMO_COLLAPSE_ANIMATION_DURATION_MILLIS,
                easing = MotionTokens.EasingStandard,
            ),
        label = "DeleteSpacing",
    )
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
                    ),
                )
            }
        }
    val insertAnimation =
        rememberMemoItemInsertAnimation(
            memoId = uiModel.memo.id,
            shouldHoldNewMemoHidden = shouldHoldNewMemoHidden,
            shouldHoldGapReadyMemoHidden = shouldHoldGapReadyMemoHidden,
            shouldAnimateNewMemoSpace = shouldAnimateNewMemoSpace,
            shouldAnimateNewMemoReveal = shouldAnimateNewMemoReveal,
            onNewMemoSpacePrepared = onNewMemoSpacePrepared,
            onNewMemoRevealConsumed = onNewMemoRevealConsumed,
        )
    val combinedAlpha = deleteAlpha * insertAnimation.contentAlpha

    AnimatedVisibility(
        visible = !isCollapsing,
        enter = EnterTransition.None,
        exit =
            shrinkVertically(
                animationSpec =
                    androidx.compose.animation.core.tween(
                        durationMillis = MEMO_COLLAPSE_ANIMATION_DURATION_MILLIS,
                        easing = MotionTokens.EasingStandard,
                    ),
                shrinkTowards = Alignment.Top,
            ),
        modifier =
            modifier.memoInsertSpaceModifier(
                spaceFraction = insertAnimation.spaceFraction,
                bottomSpacing = animatedBottomSpacing,
            ),
    ) {
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
            modifier =
                Modifier.memoVisibilityModifier(
                    alpha = combinedAlpha,
                    keepStableAlphaLayer = deleteAnimationPolicy.keepStableAlphaLayer,
                ),
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.memoListPlacementAnimation(
    lazyItemScope: androidx.compose.foundation.lazy.LazyItemScope,
    deleteAnimationPolicy: DeleteAnimationVisualPolicy,
    newMemoInsertAnimationState: NewMemoInsertAnimationState,
): Modifier =
    with(lazyItemScope) {
        this@memoListPlacementAnimation.animateItem(
            fadeInSpec = null,
            fadeOutSpec = null,
            placementSpec =
                if (deleteAnimationPolicy.animatePlacement && !newMemoInsertAnimationState.blocksPlacementSpring) {
                    spring(
                        stiffness = Spring.StiffnessLow,
                        dampingRatio = Spring.DampingRatioNoBouncy,
                    )
                } else {
                    snap()
                },
        )
    }

private fun Modifier.memoVisibilityModifier(
    alpha: Float,
    keepStableAlphaLayer: Boolean,
): Modifier =
    if (keepStableAlphaLayer || alpha < MEMO_ITEM_ALPHA_THRESHOLD) {
        then(
            Modifier.graphicsLayer {
                this.alpha = alpha
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
        )
    } else {
        this
    }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BoxScope.MemoListPullToRefreshIndicator(
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
