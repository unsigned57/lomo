package com.lomo.app.feature.trash

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.lomo.app.R
import com.lomo.domain.model.Memo
import com.lomo.ui.component.card.MemoCard
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBackClick: () -> Unit,
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val pagedTrash = viewModel.pagedTrash.collectAsLazyPagingItems()
    val dateFormat by viewModel.dateFormat.collectAsStateWithLifecycle()
    val timeFormat by viewModel.timeFormat.collectAsStateWithLifecycle()
    var selectedMemo by remember { mutableStateOf<Memo?>(null) }
    val pendingMutations by viewModel.pendingMutations.collectAsStateWithLifecycle()
    val rootDir by viewModel.rootDirectory.collectAsStateWithLifecycle()
    val imageDir by viewModel.imageDirectory.collectAsStateWithLifecycle()
    val imageMap by viewModel.imageMap.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
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
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            val hasItems = pagedTrash.itemCount > 0
            val refreshState = pagedTrash.loadState.refresh
            val isEmpty = !hasItems && refreshState is LoadState.NotLoading
            val isInitiallyLoading = refreshState is LoadState.Loading && !hasItems

            when {
                isInitiallyLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                refreshState is LoadState.Error && !hasItems -> {
                    Text(
                        text =
                            refreshState.error.message ?: androidx.compose.ui.res
                                .stringResource(R.string.error_unknown),
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                isEmpty -> {
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

                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(count = pagedTrash.itemCount, key = pagedTrash.itemKey { it.id }) { index ->
                            val memo = pagedTrash[index]
                            if (memo != null) {
                                val mutation = pendingMutations[memo.id]
                                val isVisible = mutation?.isHidden != true
                                val isProcessing = mutation != null
                                // Capture animation start time locally when processing begins
                                val mutationTimestamp = remember(isProcessing, memo.id) {
                                    if (isProcessing) System.currentTimeMillis() else null
                                }

                                if (!isVisible) {
                                    // Once marked as hidden optimistically, we effectively "remove" it from UI
                                    // by rendering an empty placeholder that doesn't take space.
                                    Box(Modifier.size(0.dp))
                                } else {
                                    // Time-Deterministic Animation Sequence
                                    // Total Duration: 300ms (Synced Fade + Shrink)
                                    val totalDuration = 300f
                                    val fadeDuration = 300f

                                    val animationProgress by androidx.compose.runtime.produceState(
                                        initialValue = 0f,
                                        isProcessing,
                                        memo.id,
                                    ) {
                                        if (isProcessing) {
                                            while (value < 1f) {
                                                val elapsed = System.currentTimeMillis() - (mutationTimestamp ?: System.currentTimeMillis())
                                                value = (elapsed.toFloat() / totalDuration).coerceIn(0f, 1f)
                                                kotlinx.coroutines.delay(16)
                                            }
                                        } else {
                                            value = 0f
                                        }
                                    }

                                    // Derived states from progress
                                    val alpha = (1f - (animationProgress / (fadeDuration / totalDuration))).coerceIn(0f, 1f)
                                    val heightScale =
                                        if (animationProgress < (fadeDuration / totalDuration)) {
                                            1f
                                        } else {
                                            (1f - ((animationProgress - 0.5f) / 0.5f)).coerceIn(0f, 1f)
                                        }

                                    // On-demand Mapping with caching (Pure Paging Stability)
                                    val uiModel =
                                        remember(memo.id, memo.content, isProcessing, rootDir, imageDir, imageMap) {
                                            viewModel.mapper.mapToUiModel(
                                                memo = memo,
                                                rootPath = rootDir,
                                                imagePath = imageDir,
                                                imageMap = imageMap,
                                                isDeleting = isProcessing,
                                            )
                                        }

                                    // Time-aware scaled container for stable resizing during recycling
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
                                                    fadeOutSpec = snap(),
                                                    placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                                ).fillMaxWidth()
                                                .alpha(alpha)
                                                .let { baseModifier ->
                                                    if (heightScale < 1f) {
                                                        baseModifier
                                                            .graphicsLayer {
                                                                scaleY = heightScale
                                                                transformOrigin =
                                                                    androidx.compose.ui.graphics
                                                                        .TransformOrigin(0.5f, 0f)
                                                            }.layout { measurable, constraints ->
                                                                val placeable = measurable.measure(constraints)
                                                                val h = (placeable.height * heightScale).toInt()
                                                                layout(placeable.width, h) {
                                                                    placeable.placeRelative(0, 0)
                                                                }
                                                            }
                                                    } else {
                                                        baseModifier
                                                    }
                                                },
                                    ) {
                                        if (heightScale > 0.01f) {
                                            Box(modifier = Modifier.alpha(alpha)) {
                                                MemoCard(
                                                    content = memo.content,
                                                    processedContent = uiModel.processedContent,
                                                    precomputedNode = uiModel.markdownNode,
                                                    timestamp = memo.timestamp,
                                                    dateFormat = dateFormat,
                                                    timeFormat = timeFormat,
                                                    tags = memo.tags.toImmutableList(),
                                                    onMenuClick = {
                                                        haptic.medium()
                                                        selectedMemo = memo
                                                    },
                                                    menuContent = {
                                                        val expanded = selectedMemo == memo
                                                        DropdownMenu(
                                                            expanded = expanded,
                                                            onDismissRequest = { selectedMemo = null },
                                                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer),
                                                            shape = RoundedCornerShape(12.dp),
                                                        ) {
                                                            DropdownMenuItem(
                                                                text = {
                                                                    Text(
                                                                        androidx.compose.ui.res
                                                                            .stringResource(R.string.action_restore),
                                                                    )
                                                                },
                                                                leadingIcon = {
                                                                    Icon(
                                                                        Icons.AutoMirrored.Filled.ArrowBack,
                                                                        contentDescription = null,
                                                                    )
                                                                },
                                                                onClick = {
                                                                    haptic.medium()
                                                                    viewModel.restoreMemo(memo)
                                                                    selectedMemo = null
                                                                },
                                                            )
                                                            DropdownMenuItem(
                                                                text = {
                                                                    Text(
                                                                        androidx.compose.ui.res
                                                                            .stringResource(R.string.action_delete_permanently),
                                                                        color = MaterialTheme.colorScheme.error,
                                                                    )
                                                                },
                                                                leadingIcon = {
                                                                    Icon(
                                                                        Icons.Default.DeleteForever,
                                                                        contentDescription = null,
                                                                        tint = MaterialTheme.colorScheme.error,
                                                                    )
                                                                },
                                                                onClick = {
                                                                    haptic.heavy()
                                                                    viewModel.deletePermanently(memo)
                                                                    selectedMemo = null
                                                                },
                                                            )
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
