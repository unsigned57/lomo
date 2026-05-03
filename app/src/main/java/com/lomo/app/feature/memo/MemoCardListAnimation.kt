package com.lomo.app.feature.memo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lomo.app.feature.common.rememberRetainedVisibleItems
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.image.createImageViewerRequest
import com.lomo.app.feature.main.DeleteViewportEntryCompensationState
import com.lomo.app.feature.main.DeleteViewportEntryPlacement
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.feature.main.deleteViewportEntryCompensation
import com.lomo.app.feature.main.rememberDeleteViewportEntryCompensation
import com.lomo.domain.model.Memo
import com.lomo.ui.component.common.WithDraggableScrollbar
import com.lomo.ui.component.menu.MemoMenuState
import com.lomo.ui.theme.MotionTokens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.delay

enum class MemoCardListAnimation {
    None,
    FadeIn,
    Placement,
}

private val MEMO_CARD_LIST_ITEM_SPACING = 12.dp
private const val MEMO_CARD_DELETE_FADE_DURATION_MILLIS = 300
private const val MEMO_CARD_DELETE_COLLAPSE_DURATION_MILLIS = 300

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MemoCardList(
    memos: ImmutableList<MemoUiModel>,
    dateFormat: String,
    timeFormat: String,
    doubleTapEditEnabled: Boolean,
    onMemoEdit: (Memo) -> Unit,
    onShowMenu: (MemoMenuState) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState? = null,
    freeTextCopyEnabled: Boolean = false,
    onImageClick: (ImageViewerRequest) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(16.dp),
    animation: MemoCardListAnimation = MemoCardListAnimation.FadeIn,
    showScrollbar: Boolean = false,
    deletingMemoIds: ImmutableSet<String> = persistentSetOf(),
    onDeleteAnimationSettled: (String) -> Unit = {},
) {
    val resolvedListState = listState ?: rememberLazyListState()
    val visibleMemos =
        rememberRetainedVisibleItems(
            sourceItems = memos,
            retainedIds = deletingMemoIds,
            itemId = { it.memo.id },
            onRetentionSettled = onDeleteAnimationSettled,
        )
    val viewportEntryCompensation =
        rememberDeleteViewportEntryCompensation(
            sourceItems = visibleMemos,
            deletingIds = deletingMemoIds,
            listState = resolvedListState,
        )
    val listContent: @Composable () -> Unit = {
        LazyColumn(
            state = resolvedListState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.Top,
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(
                items = visibleMemos,
                key = { _, item -> item.memo.id },
                contentType = { _, _ -> "memo" },
            ) { index, uiModel ->
                val deleteViewportSharedCompensation =
                    viewportEntryCompensation.sharedTopEntryCompensationFor(uiModel.memo.id)
                val deleteViewportCompensation =
                    deleteViewportSharedCompensation
                        ?: viewportEntryCompensation.compensationFor(uiModel.memo.id)
                val deleteViewportHoldOffset =
                    if (deleteViewportCompensation == null) {
                        viewportEntryCompensation.holdOffsetFor(uiModel.memo.id)
                    } else {
                        null
                    }
                MemoCardAnimatedItem(
                    uiModel = uiModel,
                    itemIndex = index,
                    itemCount = visibleMemos.size,
                    dateFormat = dateFormat,
                    timeFormat = timeFormat,
                    doubleTapEditEnabled = doubleTapEditEnabled,
                    freeTextCopyEnabled = freeTextCopyEnabled,
                    onMemoEdit = onMemoEdit,
                    onShowMenu = onShowMenu,
                    onImageClick = onImageClick,
                    animation = animation,
                    isDeleting = uiModel.memo.id in deletingMemoIds,
                    deleteAnimationEnabled = deletingMemoIds.isNotEmpty(),
                    viewportEntryCompensation = viewportEntryCompensation,
                    deleteViewportCompensation = deleteViewportCompensation,
                    deleteViewportHoldOffset = deleteViewportHoldOffset,
                )
            }
        }
    }

    if (showScrollbar) {
        WithDraggableScrollbar(
            state = resolvedListState,
            modifier = modifier.fillMaxSize(),
        ) {
            listContent()
        }
    } else {
        Box(modifier = modifier.fillMaxSize()) {
            listContent()
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun androidx.compose.foundation.lazy.LazyItemScope.MemoCardAnimatedItem(
    uiModel: MemoUiModel,
    itemIndex: Int,
    itemCount: Int,
    dateFormat: String,
    timeFormat: String,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    onMemoEdit: (Memo) -> Unit,
    onShowMenu: (MemoMenuState) -> Unit,
    onImageClick: (ImageViewerRequest) -> Unit,
    animation: MemoCardListAnimation,
    isDeleting: Boolean,
    deleteAnimationEnabled: Boolean,
    viewportEntryCompensation: DeleteViewportEntryCompensationState,
    deleteViewportCompensation: DeleteViewportEntryPlacement?,
    deleteViewportHoldOffset: Float?,
) {
    val density = LocalDensity.current
    val bottomSpacing = if (itemIndex == itemCount - 1) 0.dp else MEMO_CARD_LIST_ITEM_SPACING
    val bottomSpacingPx =
        remember(bottomSpacing, density) {
            with(density) { bottomSpacing.roundToPx() }
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
    val itemModifier =
        Modifier.rememberMemoCardItemModifier(
            lazyItemScope = this,
            animation = animation,
            memoId = uiModel.memo.id,
            enableDeletePlacementAnimation = deleteAnimationEnabled,
            blockPlacementSpringForDeleteViewportEntry =
                deleteViewportCompensation != null ||
                    deleteViewportHoldOffset != null,
        )
            .deleteViewportEntryCompensation(
                compensation = deleteViewportCompensation,
                holdOffsetPx = deleteViewportHoldOffset,
                onAnimationConsumed = {
                    viewportEntryCompensation.clearCompensation(uiModel.memo.id)
                },
            )
            .onSizeChanged { size ->
                viewportEntryCompensation.onItemMeasured(
                    itemId = uiModel.memo.id,
                    itemIndex = itemIndex,
                    isDeleting = isDeleting,
                    heightPx = size.height,
                    bottomSpacingPx = bottomSpacingPx,
                )
            }

    MemoCardDeleteAnimatedContainer(
        uiModel = uiModel,
        isDeleting = isDeleting,
        bottomSpacing = bottomSpacing,
        dateFormat = dateFormat,
        timeFormat = timeFormat,
        doubleTapEditEnabled = doubleTapEditEnabled,
        freeTextCopyEnabled = freeTextCopyEnabled,
        onMemoEdit = onMemoEdit,
        onShowMenu = onShowMenu,
        onImageClick = stableImageClick,
        modifier = itemModifier,
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Modifier.rememberMemoCardItemModifier(
    lazyItemScope: androidx.compose.foundation.lazy.LazyItemScope,
    animation: MemoCardListAnimation,
    memoId: String,
    enableDeletePlacementAnimation: Boolean,
    blockPlacementSpringForDeleteViewportEntry: Boolean,
): Modifier =
    when (animation) {
        MemoCardListAnimation.None ->
            memoCardDeletePlacementAnimation(
                lazyItemScope = lazyItemScope,
                enabled = enableDeletePlacementAnimation && !blockPlacementSpringForDeleteViewportEntry,
            )

        MemoCardListAnimation.FadeIn -> {
            val animatedAlpha = remember { Animatable(0f) }
            LaunchedEffect(memoId) {
                animatedAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec =
                        androidx.compose.animation.core.tween(
                            durationMillis = MotionTokens.DurationLong2,
                            easing = MotionTokens.EasingStandard,
                        ),
                )
            }
            this.graphicsLayer {
                alpha = animatedAlpha.value
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }.memoCardDeletePlacementAnimation(
                lazyItemScope = lazyItemScope,
                enabled = enableDeletePlacementAnimation && !blockPlacementSpringForDeleteViewportEntry,
            )
        }

        MemoCardListAnimation.Placement -> {
            with(lazyItemScope) {
                this@rememberMemoCardItemModifier.animateItem(
                    fadeInSpec =
                         keyframes {
                             durationMillis = PLACEMENT_FADE_IN_DURATION_MS
                             0f at 0
                             0f at MotionTokens.DurationLong2
                             1f at PLACEMENT_FADE_IN_DURATION_MS using MotionTokens.EasingEmphasizedDecelerate
                         },
                     fadeOutSpec = snap(),
                    placementSpec =
                        if (blockPlacementSpringForDeleteViewportEntry) {
                            snap()
                        } else {
                            spring(
                                stiffness = Spring.StiffnessLow,
                                dampingRatio = Spring.DampingRatioNoBouncy,
                            )
                        },
                )
            }
        }
    }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.memoCardDeletePlacementAnimation(
    lazyItemScope: androidx.compose.foundation.lazy.LazyItemScope,
    enabled: Boolean,
): Modifier {
    if (!enabled) {
        return this
    }
    return with(lazyItemScope) {
        this@memoCardDeletePlacementAnimation.animateItem(
            fadeInSpec = null,
            fadeOutSpec = snap(),
            placementSpec =
                spring(
                    stiffness = Spring.StiffnessLow,
                    dampingRatio = Spring.DampingRatioNoBouncy,
                ),
        )
    }
}

@Composable
private fun MemoCardDeleteAnimatedContainer(
    uiModel: MemoUiModel,
    isDeleting: Boolean,
    bottomSpacing: Dp,
    dateFormat: String,
    timeFormat: String,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    onMemoEdit: (Memo) -> Unit,
    onShowMenu: (MemoMenuState) -> Unit,
    onImageClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isCollapsing by remember { mutableStateOf(false) }

    LaunchedEffect(isDeleting) {
        if (isDeleting) {
            delay(MEMO_CARD_DELETE_FADE_DURATION_MILLIS.toLong())
            isCollapsing = true
        } else {
            isCollapsing = false
        }
    }

    val itemAlpha by animateFloatAsState(
        targetValue = if (isDeleting) 0f else 1f,
        animationSpec =
            tween(
                durationMillis = MEMO_CARD_DELETE_FADE_DURATION_MILLIS,
                easing = MotionTokens.EasingStandard,
            ),
        label = "MemoCardDeleteAlpha",
    )

    val animatedBottomSpacing by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (isCollapsing) 0.dp else bottomSpacing,
        animationSpec =
            tween(
                durationMillis = MEMO_CARD_DELETE_COLLAPSE_DURATION_MILLIS,
                easing = MotionTokens.EasingStandard,
            ),
        label = "MemoCardDeleteSpacing",
    )

    AnimatedVisibility(
        visible = !isCollapsing,
        enter = androidx.compose.animation.EnterTransition.None,
        exit =
            shrinkVertically(
                animationSpec =
                    tween(
                        durationMillis = MEMO_CARD_DELETE_COLLAPSE_DURATION_MILLIS,
                        easing = MotionTokens.EasingStandard,
                    ),
                shrinkTowards = Alignment.Top,
            ),
        modifier = modifier.then(Modifier.padding(bottom = animatedBottomSpacing)),
    ) {
        Box(
            modifier =
                Modifier.graphicsLayer {
                    alpha = itemAlpha
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
        ) {
            MemoCardEntry(
                uiModel = uiModel,
                dateFormat = dateFormat,
                timeFormat = timeFormat,
                doubleTapEditEnabled = doubleTapEditEnabled,
                freeTextCopyEnabled = freeTextCopyEnabled,
                onMemoEdit = onMemoEdit,
                onShowMenu = onShowMenu,
                onImageClick = onImageClick,
            )
        }
    }
}

private const val PLACEMENT_FADE_IN_DURATION_MS = 1000
