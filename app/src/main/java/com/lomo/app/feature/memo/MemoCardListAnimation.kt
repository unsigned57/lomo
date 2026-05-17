package com.lomo.app.feature.memo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.feature.main.updateExpandedMemoIds
import com.lomo.domain.model.Memo
import com.lomo.ui.component.common.LazyListItemEntranceState
import com.lomo.ui.component.common.LazyListItemPlacementMode
import com.lomo.ui.component.common.LazyListMotionState
import com.lomo.ui.component.common.WithDraggableScrollbar
import com.lomo.ui.component.common.lazyListMotionItem
import com.lomo.ui.component.common.rememberLazyListMotionState
import com.lomo.ui.component.common.resolveLazyListItemMotionPolicy
import com.lomo.ui.component.common.toLazyListMotionViewportSnapshot
import com.lomo.ui.component.menu.MemoMenuState
import com.lomo.ui.theme.MotionTokens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.delay

enum class MemoCardListAnimation {
    None,
    FadeIn,
    Placement,
}

internal enum class MemoCardListEntranceState {
    Active,
    Settled,
}

internal data class MemoCardListItemMotionPolicy(
    val usesLazyItemFadeIn: Boolean,
    val usesPlacementSpring: Boolean,
)

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
    onTodoClick: ((Memo, Int, Boolean) -> Unit)? = null,
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
    val motionItemKeys =
        remember(visibleMemos) {
            visibleMemos.map { uiModel -> uiModel.memo.id }.toPersistentList()
        }
    val listMotionState =
        rememberLazyListMotionState(
            itemKeys = motionItemKeys,
            removingKeys = deletingMemoIds,
            listState = resolvedListState,
        )
    var expandedMemoIds by rememberSaveable(saver = memoCardExpandedMemoIdsSaver()) {
        mutableStateOf(persistentSetOf<String>())
    }
    var entranceState by remember(animation) {
        mutableStateOf(
            if (animation == MemoCardListAnimation.Placement) {
                MemoCardListEntranceState.Active
            } else {
                MemoCardListEntranceState.Settled
            },
        )
    }

    LaunchedEffect(animation) {
        if (animation == MemoCardListAnimation.Placement) {
            entranceState = MemoCardListEntranceState.Active
            delay(PLACEMENT_FADE_IN_DURATION_MS.toLong())
        }
        entranceState = MemoCardListEntranceState.Settled
    }

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
                    onTodoClick = onTodoClick,
                    animation = animation,
                    entranceState = entranceState,
                    isDeleting = uiModel.memo.id in deletingMemoIds,
                    deleteAnimationEnabled = deletingMemoIds.isNotEmpty(),
                    listMotionState = listMotionState,
                    isExpanded = uiModel.memo.id in expandedMemoIds,
                    onExpandedChange = { expanded ->
                        listMotionState.beginResizeTransition(
                            itemId = uiModel.memo.id,
                            expands = expanded,
                            snapshot = resolvedListState.layoutInfo.toLazyListMotionViewportSnapshot(),
                        )
                        expandedMemoIds =
                            updateExpandedMemoIds(
                                expandedMemoIds = expandedMemoIds,
                                memoId = uiModel.memo.id,
                                isExpanded = expanded,
                            ).toPersistentSet()
                    },
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
    onTodoClick: ((Memo, Int, Boolean) -> Unit)?,
    animation: MemoCardListAnimation,
    entranceState: MemoCardListEntranceState,
    isDeleting: Boolean,
    deleteAnimationEnabled: Boolean,
    listMotionState: LazyListMotionState,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
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
                        memoId = uiModel.memo.id,
                    ),
                )
            }
        }
    val stableTodoClick: ((Int, Boolean) -> Unit)? =
        remember(uiModel.memo, onTodoClick) {
            if (onTodoClick == null) {
                null
            } else {
                { lineIndex: Int, checked: Boolean ->
                    onTodoClick.invoke(uiModel.memo, lineIndex, checked)
                }
            }
        }
    val itemModifier =
        Modifier.rememberMemoCardItemModifier(
            lazyItemScope = this,
            animation = animation,
            entranceState = entranceState,
            memoId = uiModel.memo.id,
            enableDeletePlacementAnimation = deleteAnimationEnabled,
            listMotionState = listMotionState,
        )
            .onSizeChanged { size ->
                listMotionState.onItemMeasured(
                    itemId = uiModel.memo.id,
                    itemIndex = itemIndex,
                    isRemoving = isDeleting,
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
        onTodoClick = stableTodoClick,
        modifier = itemModifier,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Modifier.rememberMemoCardItemModifier(
    lazyItemScope: androidx.compose.foundation.lazy.LazyItemScope,
    animation: MemoCardListAnimation,
    entranceState: MemoCardListEntranceState,
    memoId: String,
    enableDeletePlacementAnimation: Boolean,
    listMotionState: LazyListMotionState,
): Modifier {
    val motionPolicy =
        resolveMemoCardListItemMotionPolicy(
            animation = animation,
            entranceState = entranceState,
            deleteAnimationEnabled = enableDeletePlacementAnimation,
            blockPlacementSpringForDeleteViewportEntry = false,
            blockPlacementSpringForMemoExpansion = listMotionState.structureMotionActiveFor(memoId),
        )
    val placementMode =
        if (motionPolicy.usesPlacementSpring) {
            LazyListItemPlacementMode.Spring
        } else {
            LazyListItemPlacementMode.Disabled
        }
    val itemEntranceState =
        if (motionPolicy.usesLazyItemFadeIn) {
            LazyListItemEntranceState.Active
        } else {
            LazyListItemEntranceState.Settled
        }
    return when (animation) {
        MemoCardListAnimation.None ->
            lazyListMotionItem(
                lazyItemScope = lazyItemScope,
                itemKey = memoId,
                motionState = listMotionState,
                entranceState = itemEntranceState,
                placementMode = placementMode,
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
            }.lazyListMotionItem(
                lazyItemScope = lazyItemScope,
                itemKey = memoId,
                motionState = listMotionState,
                entranceState = itemEntranceState,
                placementMode = placementMode,
            )
        }

        MemoCardListAnimation.Placement ->
            lazyListMotionItem(
                lazyItemScope = lazyItemScope,
                itemKey = memoId,
                motionState = listMotionState,
                entranceState = itemEntranceState,
                placementMode = placementMode,
            )
    }
}

internal fun resolveMemoCardListItemMotionPolicy(
    animation: MemoCardListAnimation,
    entranceState: MemoCardListEntranceState,
    deleteAnimationEnabled: Boolean,
    blockPlacementSpringForDeleteViewportEntry: Boolean,
    blockPlacementSpringForMemoExpansion: Boolean,
): MemoCardListItemMotionPolicy {
    val structureMotionActive =
        blockPlacementSpringForDeleteViewportEntry || blockPlacementSpringForMemoExpansion
    val placementMode =
        when (animation) {
            MemoCardListAnimation.None,
            MemoCardListAnimation.FadeIn,
            -> if (deleteAnimationEnabled) LazyListItemPlacementMode.Spring else LazyListItemPlacementMode.Disabled

            MemoCardListAnimation.Placement -> LazyListItemPlacementMode.Spring
        }
    val policy =
        resolveLazyListItemMotionPolicy(
            entranceState =
                if (
                    animation == MemoCardListAnimation.Placement &&
                    entranceState == MemoCardListEntranceState.Active
                ) {
                    LazyListItemEntranceState.Active
                } else {
                    LazyListItemEntranceState.Settled
                },
            placementMode = placementMode,
            structureMotionActive = structureMotionActive,
        )
    return MemoCardListItemMotionPolicy(
        usesLazyItemFadeIn = policy.usesLazyItemFadeIn,
        usesPlacementSpring = policy.usesPlacementSpring,
    )
}

private fun memoCardExpandedMemoIdsSaver() =
    listSaver<androidx.compose.runtime.MutableState<ImmutableSet<String>>, String>(
        save = { state -> state.value.toList() },
        restore = { restored -> mutableStateOf(restored.toPersistentSet()) },
    )


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
    onTodoClick: ((Int, Boolean) -> Unit)?,
    isExpanded: Boolean,
    modifier: Modifier = Modifier,
    onExpandedChange: (Boolean) -> Unit,
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
                onTodoClick = onTodoClick,
                isExpanded = isExpanded,
                onExpandedChange = onExpandedChange,
            )
        }
    }
}

private const val PLACEMENT_FADE_IN_DURATION_MS = 1000
