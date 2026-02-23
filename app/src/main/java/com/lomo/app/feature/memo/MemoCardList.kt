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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.domain.model.Memo
import com.lomo.ui.component.card.MemoCard
import com.lomo.ui.component.menu.MemoMenuState
import com.lomo.ui.theme.MotionTokens
import com.lomo.ui.util.formatAsDateTime

enum class MemoCardListAnimation {
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
    onImageClick: (String) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(16.dp),
    animation: MemoCardListAnimation = MemoCardListAnimation.FadeIn,
    modifier: Modifier = Modifier,
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
            val memo = uiModel.memo

            val itemModifier =
                when (animation) {
                    MemoCardListAnimation.FadeIn -> {
                        val animatedAlpha = remember { Animatable(0f) }
                        LaunchedEffect(memo.id) {
                            animatedAlpha.animateTo(
                                targetValue = 1f,
                                animationSpec =
                                    androidx.compose.animation.core.tween(
                                        durationMillis = MotionTokens.DurationLong2,
                                        easing = MotionTokens.EasingStandard,
                                    ),
                            )
                        }
                        Modifier.graphicsLayer { alpha = animatedAlpha.value }
                    }

                    MemoCardListAnimation.Placement -> {
                        Modifier.animateItem(
                            fadeInSpec =
                                keyframes {
                                    durationMillis = 1000
                                    0f at 0
                                    0f at MotionTokens.DurationLong2
                                    1f at 1000 using MotionTokens.EasingEmphasizedDecelerate
                                },
                            fadeOutSpec = snap(),
                            placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        )
                    }
                }

            Box(modifier = itemModifier) {
                MemoCard(
                    content = memo.content,
                    processedContent = uiModel.processedContent,
                    precomputedNode = uiModel.markdownNode,
                    timestamp = memo.timestamp,
                    dateFormat = dateFormat,
                    timeFormat = timeFormat,
                    tags = uiModel.tags,
                    onDoubleClick =
                        if (doubleTapEditEnabled) {
                            { onMemoEdit(memo) }
                        } else {
                            null
                        },
                    onImageClick = onImageClick,
                    onMenuClick = {
                        onShowMenu(
                            MemoMenuState(
                                wordCount = memo.content.length,
                                createdTime = memo.timestamp.formatAsDateTime(dateFormat, timeFormat),
                                content = memo.content,
                                memo = memo,
                            ),
                        )
                    },
                    menuContent = {},
                )
            }
        }
    }
}
