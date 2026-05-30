package com.lomo.app.feature.main

import androidx.collection.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.imageLoader
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.image.enqueueImagePreloadRequests
import com.lomo.app.feature.image.createImageViewerRequest
import com.lomo.app.feature.memo.MemoCardEntry
import com.lomo.domain.model.Memo
import com.lomo.app.feature.memo.MemoMenuSelection
import com.lomo.ui.theme.MotionTokens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

private const val PRELOAD_LOOKAHEAD_COUNT = 5
private const val STARTUP_PRELOAD_MEMO_COUNT = 6
private const val PRELOAD_EVENT_THROTTLE_MS = 150L
private const val PRELOAD_URL_DEDUPE_MS = 12_000L
private const val PRELOAD_TRACKED_URL_LIMIT = 512
private const val PRELOAD_SIGNATURE_HASH_MULTIPLIER = 31

internal val MEMO_LIST_HORIZONTAL_PADDING = 16.dp
internal val MEMO_LIST_TOP_PADDING = 16.dp
internal val MEMO_LIST_BOTTOM_PADDING = 88.dp
internal val MEMO_LIST_ITEM_SPACING = 12.dp
private const val MEMO_ITEM_HIDDEN_ALPHA = 0f
private const val MEMO_ITEM_VISIBLE_ALPHA = 1f
private const val MEMO_ITEM_ALPHA_THRESHOLD = 0.999f
private const val MEMO_DELETE_FADE_DURATION_MILLIS = 300
private const val MEMO_COLLAPSE_ANIMATION_DURATION_MILLIS = 300

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
    memos: ImmutableList<MemoUiModel>,
    listState: LazyListState,
) {
    val context = LocalContext.current
    val imageLoader = context.imageLoader
    val preloadGate = rememberSaveable(saver = ImagePreloadGate.Saver) { ImagePreloadGate() }
    val latestMemos by rememberUpdatedState(memos)
    val startupPreloadMemoSignature =
        remember(memos) {
            memos.take(STARTUP_PRELOAD_MEMO_COUNT)
                .fold(0) { acc, memo ->
                    PRELOAD_SIGNATURE_HASH_MULTIPLIER * acc + memo.memo.id.hashCode()
                }
        }

    LaunchedEffect(startupPreloadMemoSignature, context, imageLoader, preloadGate) {
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
                        preloadGate.selectPriorityUrlsToEnqueue(preloadCandidates)
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
    memos: ImmutableList<MemoUiModel>,
    startupMemoCount: Int,
): List<String> =
    memos
        .asSequence()
        .take(startupMemoCount.coerceAtLeast(0))
        .flatMap { memo -> memo.imageUrls.asSequence() }
        .toList()

private fun buildVisibleAndLookaheadImagePreloadCandidates(
    memos: ImmutableList<MemoUiModel>,
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

@Composable
internal fun MemoListItem(
    uiModel: MemoUiModel,
    isDeleting: Boolean,
    shouldHoldNewMemoHidden: Boolean,
    shouldHoldGapReadyMemoHidden: Boolean,
    shouldAnimateNewMemoSpace: Boolean,
    shouldAnimateNewMemoReveal: Boolean,
    bottomSpacing: androidx.compose.ui.unit.Dp,
    onTodoClick: (Memo, Int, Boolean) -> Unit,
    dateFormat: String,
    timeFormat: String,
    onMemoDoubleClick: (Memo) -> Unit,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    onTagClick: (String) -> Unit,
    onImageClick: (ImageViewerRequest) -> Unit,
    onShowMemoMenu: (MemoMenuSelection) -> Unit,
    onNewMemoSpacePrepared: (String) -> Unit,
    onNewMemoRevealConsumed: (String) -> Unit,
    modifier: Modifier = Modifier,
    onReminderClick: (com.lomo.domain.model.ReminderMarker) -> Unit = {},
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    // Two-phase delete animation: fade (t=0–300ms) then collapse (t=300–600ms).
    // State is keyed on memo id so paging refresh / LazyColumn slot reuse cannot leak the
    // previous row's collapse state onto a newly-bound row — the source of the reported
    // "delete plays on the wrong row" + "siblings flicker after delete" bugs.
    var isCollapsing by remember(uiModel.memo.id) { mutableStateOf(false) }

    LaunchedEffect(uiModel.memo.id, isDeleting) {
        if (isDeleting) {
            delay(MEMO_DELETE_FADE_DURATION_MILLIS.toLong())
            isCollapsing = true
        } else {
            isCollapsing = false
        }
    }

    // Phase 1: alpha fade-out (fixed spec — only target changes)
    val itemAlpha by animateFloatAsState(
        targetValue = if (isDeleting) 0f else 1f,
        animationSpec =
            tween(
                durationMillis = MEMO_DELETE_FADE_DURATION_MILLIS,
                easing = MotionTokens.EasingStandard,
            ),
        label = "DeleteAlpha-${uiModel.memo.id}",
    )

    // Phase 2: bottom spacing collapse (fixed spec — only target changes)
    val animatedBottomSpacing =
        rememberAnimatedBottomSpacing(
            isCollapsing = isCollapsing,
            bottomSpacing = bottomSpacing,
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
                        memoId = uiModel.memo.id,
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

    AnimatedVisibility(
        visible = !isCollapsing,
        enter = EnterTransition.None,
        exit =
            shrinkVertically(
                animationSpec =
                    tween(
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
                modifier =
                    Modifier
                        .graphicsLayer {
                            alpha = itemAlpha * insertAnimation.contentAlpha
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        },
            )
        }
    }
}

private fun Modifier.memoVisibilityModifier(alpha: Float): Modifier =
    if (alpha < MEMO_ITEM_ALPHA_THRESHOLD) {
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

internal class ImagePreloadGate(
    private val eventThrottleMs: Long = PRELOAD_EVENT_THROTTLE_MS,
    private val dedupeWindowMs: Long = PRELOAD_URL_DEDUPE_MS,
    private val maxTrackedUrls: Int = PRELOAD_TRACKED_URL_LIMIT,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lastEnqueueAtMsByUrl = LruCache<String, Long>(maxTrackedUrls)
    private var lastEventAtMs: Long? = null

    fun selectUrlsToEnqueue(candidates: Iterable<String>): List<String> =
        selectUrlsToEnqueue(
            candidates = candidates,
            enforceEventThrottle = true,
        )

    fun selectPriorityUrlsToEnqueue(candidates: Iterable<String>): List<String> =
        selectUrlsToEnqueue(
            candidates = candidates,
            enforceEventThrottle = false,
        )

    private fun selectUrlsToEnqueue(
        candidates: Iterable<String>,
        enforceEventThrottle: Boolean,
    ): List<String> {
        val now = nowMs()
        if (enforceEventThrottle && shouldThrottle(now)) {
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
                lastEnqueueAtMsByUrl.put(url, now)
                result += url
            }
        }
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
        val trackedUrls = lastEnqueueAtMsByUrl.snapshot()
        if (trackedUrls.isEmpty()) {
            return
        }
        trackedUrls.forEach { (url, lastEnqueueAt) ->
            if (now - lastEnqueueAt > dedupeWindowMs) {
                lastEnqueueAtMsByUrl.remove(url)
            }
        }
    }

    internal fun snapshot(): Snapshot =
        Snapshot(
            trackedUrls =
                buildList(lastEnqueueAtMsByUrl.snapshot().size * 2) {
                    lastEnqueueAtMsByUrl.snapshot().forEach { (url, timestamp) ->
                        add(url)
                        add(timestamp.toString())
                    }
                },
            lastEventAtMs = lastEventAtMs,
        )

    internal fun restore(snapshot: Snapshot) {
        lastEnqueueAtMsByUrl.evictAll()
        snapshot.trackedUrls
            .chunked(2)
            .forEach { entry ->
                if (entry.size == 2) {
                    lastEnqueueAtMsByUrl.put(entry[0], entry[1].toLong())
                }
            }
        lastEventAtMs = snapshot.lastEventAtMs
    }

    internal companion object {
        val Saver: Saver<ImagePreloadGate, Any> =
            listSaver(
                save = { gate ->
                    val snapshot = gate.snapshot()
                    buildList(snapshot.trackedUrls.size + 1) {
                        add(snapshot.lastEventAtMs?.toString().orEmpty())
                        addAll(snapshot.trackedUrls)
                    }
                },
                restore = { restored ->
                    ImagePreloadGate().apply {
                        restore(
                            Snapshot(
                                trackedUrls = restored.drop(1),
                                lastEventAtMs = restored.firstOrNull()?.takeIf(String::isNotEmpty)?.toLong(),
                            ),
                        )
                    }
                },
            )
    }

    internal data class Snapshot(
        val trackedUrls: List<String>,
        val lastEventAtMs: Long?,
    )
}

internal val MemoUiModel.memoListItemContentBucket: String
    get() = if (imageUrls.isEmpty()) "memo-text" else "memo-media"
