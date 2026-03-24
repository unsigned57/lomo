package com.lomo.app.feature.memo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.image.createImageViewerRequest
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.domain.model.Memo
import com.lomo.ui.component.menu.MemoMenuState
import com.lomo.ui.theme.MotionTokens

enum class MemoCardListAnimation {
    None,
    FadeIn,
    Placement,
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MemoCardList(
    memos: List<MemoUiModel>,
    dateFormat: String,
    timeFormat: String,
    doubleTapEditEnabled: Boolean,
    onMemoEdit: (Memo) -> Unit,
    onShowMenu: (MemoMenuState) -> Unit,
    modifier: Modifier = Modifier,
    freeTextCopyEnabled: Boolean = false,
    onImageClick: (ImageViewerRequest) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(16.dp),
    animation: MemoCardListAnimation = MemoCardListAnimation.FadeIn,
) {
    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        items(
            items = memos,
            key = { it.memo.id },
            contentType = { "memo" },
        ) { uiModel ->
            MemoCardAnimatedItem(
                uiModel = uiModel,
                dateFormat = dateFormat,
                timeFormat = timeFormat,
                doubleTapEditEnabled = doubleTapEditEnabled,
                freeTextCopyEnabled = freeTextCopyEnabled,
                onMemoEdit = onMemoEdit,
                onShowMenu = onShowMenu,
                onImageClick = onImageClick,
                animation = animation,
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun androidx.compose.foundation.lazy.LazyItemScope.MemoCardAnimatedItem(
    uiModel: MemoUiModel,
    dateFormat: String,
    timeFormat: String,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    onMemoEdit: (Memo) -> Unit,
    onShowMenu: (MemoMenuState) -> Unit,
    onImageClick: (ImageViewerRequest) -> Unit,
    animation: MemoCardListAnimation,
) {
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
        )

    Box(modifier = itemModifier) {
        MemoCardEntry(
            uiModel = uiModel,
            dateFormat = dateFormat,
            timeFormat = timeFormat,
            doubleTapEditEnabled = doubleTapEditEnabled,
            freeTextCopyEnabled = freeTextCopyEnabled,
            onMemoEdit = onMemoEdit,
            onShowMenu = onShowMenu,
            onImageClick = stableImageClick,
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Modifier.rememberMemoCardItemModifier(
    lazyItemScope: androidx.compose.foundation.lazy.LazyItemScope,
    animation: MemoCardListAnimation,
    memoId: String,
): Modifier =
    when (animation) {
        MemoCardListAnimation.None -> this

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
            }
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
                    placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                )
            }
        }
    }

private const val PLACEMENT_FADE_IN_DURATION_MS = 1000
