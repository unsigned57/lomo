package com.lomo.app.feature.trash

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.app.feature.memo.memoMenuState
import com.lomo.domain.model.Memo
import com.lomo.ui.component.card.MemoCard
import com.lomo.ui.component.menu.MemoActionHaptic
import com.lomo.ui.component.menu.MemoActionSheet
import com.lomo.ui.component.menu.MemoActionSheetAction
import com.lomo.ui.component.menu.MemoMenuState

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TrashScreen(
    onBackClick: () -> Unit,
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val trashMemos by viewModel.trashUiMemos.collectAsStateWithLifecycle()
    val appPreferences by viewModel.appPreferences.collectAsStateWithLifecycle()
    val dateFormat = appPreferences.dateFormat
    val timeFormat = appPreferences.timeFormat

    var selectedMemo by remember { mutableStateOf<Memo?>(null) }
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    val listState =
        rememberSaveable(saver = LazyListState.Saver) {
            LazyListState()
        }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        androidx.compose.ui.res
                            .stringResource(R.string.trash_title),
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            haptic.medium()
                            onBackClick()
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription =
                                androidx.compose.ui.res
                                    .stringResource(R.string.back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
            )
        },
    ) { paddingValues ->
        if (trashMemos.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                com.lomo.ui.component.common.EmptyState(
                    icon = Icons.Rounded.DeleteSweep,
                    title =
                        androidx.compose.ui.res
                            .stringResource(R.string.trash_empty_title),
                    description =
                        androidx.compose.ui.res
                            .stringResource(R.string.trash_empty_desc),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().padding(paddingValues),
            ) {
                items(
                    items = trashMemos,
                    key = { it.memo.id },
                    contentType = { "memo" },
                ) { uiModel ->
                    val memo = uiModel.memo
                    val deleteAlpha by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = if (uiModel.isDeleting) 0f else 1f,
                        animationSpec =
                            androidx.compose.animation.core.tween(
                                durationMillis = 300,
                                easing = com.lomo.ui.theme.MotionTokens.EasingStandard,
                            ),
                        label = "DeleteAlpha",
                    )

                    Box(
                        modifier =
                            Modifier
                                .animateItem(
                                    fadeInSpec =
                                        keyframes {
                                            durationMillis = 300
                                            0f at 0
                                            0f at 300
                                            1f at 300 using com.lomo.ui.theme.MotionTokens.EasingEmphasizedDecelerate
                                        },
                                    fadeOutSpec = null,
                                    placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                ).fillMaxWidth()
                                .graphicsLayer {
                                    this.alpha = deleteAlpha
                                    compositingStrategy = CompositingStrategy.ModulateAlpha
                                },
                    ) {
                        MemoCard(
                            content = memo.content,
                            processedContent = uiModel.processedContent,
                            precomputedNode = uiModel.markdownNode,
                            timestamp = memo.timestamp,
                            dateFormat = dateFormat,
                            timeFormat = timeFormat,
                            tags = uiModel.tags,
                            onMenuClick = {
                                haptic.medium()
                                selectedMemo = memo
                            },
                        )
                    }
                }
            }
        }
    }

    selectedMemo?.let { memo ->
        val menuState = remember(memo, dateFormat, timeFormat) { memoMenuState(memo, dateFormat, timeFormat) }
        TrashActionSheet(
            state = menuState,
            onDismiss = { selectedMemo = null },
            onRestore = { viewModel.restoreMemo(memo) },
            onDeletePermanently = { viewModel.deletePermanently(memo) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrashActionSheet(
    state: MemoMenuState,
    onDismiss: () -> Unit,
    onRestore: () -> Unit,
    onDeletePermanently: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = {
            Box(
                modifier =
                    Modifier
                        .padding(vertical = 22.dp)
                        .width(32.dp)
                        .size(32.dp, 4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
            )
        },
    ) {
        MemoActionSheet(
            state = state,
            onCopy = {},
            onShare = {},
            onLanShare = {},
            onEdit = {},
            onDelete = onDeletePermanently,
            onDismiss = onDismiss,
            useHorizontalScroll = false,
            showSwipeAffordance = false,
            equalWidthActions = true,
            actions =
                listOf(
                    MemoActionSheetAction(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        label = androidx.compose.ui.res.stringResource(R.string.action_restore),
                        onClick = onRestore,
                        dismissAfterClick = true,
                        haptic = MemoActionHaptic.MEDIUM,
                    ),
                    MemoActionSheetAction(
                        icon = Icons.Default.DeleteForever,
                        label = androidx.compose.ui.res.stringResource(R.string.action_delete_permanently),
                        onClick = onDeletePermanently,
                        isDestructive = true,
                        dismissAfterClick = true,
                        haptic = MemoActionHaptic.HEAVY,
                    ),
                ),
        )
    }
}
