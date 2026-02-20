package com.lomo.app.feature.tag

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.lomo.ui.component.card.MemoCard
import com.lomo.ui.component.common.EmptyState
import com.lomo.ui.component.common.SkeletonMemoItem
import com.lomo.ui.component.menu.MemoMenuBottomSheet
import com.lomo.ui.component.menu.MemoMenuHost
import com.lomo.ui.component.menu.MemoMenuState
import com.lomo.ui.theme.MotionTokens
import com.lomo.ui.util.DateTimeUtils
import com.lomo.ui.util.formatAsDateTime
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagFilterScreen(
    tagName: String,
    onBackClick: () -> Unit,
    onNavigateToImage: (String) -> Unit,
    onNavigateToShare: (String, Long) -> Unit = { _, _ -> },
    viewModel: TagFilterViewModel = hiltViewModel(),
) {
    val pagedMemos = viewModel.pagedMemos.collectAsLazyPagingItems()
    val dateFormat by viewModel.dateFormat.collectAsStateWithLifecycle()
    val timeFormat by viewModel.timeFormat.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Track deleting items for "fade out then delete" animation sequence
    val deletingIds = remember { mutableStateListOf<String>() }
    var showInputSheet by remember { mutableStateOf(false) }
    var editingMemo by remember { mutableStateOf<com.lomo.domain.model.Memo?>(null) }
    var inputText by remember {
        mutableStateOf(
            androidx.compose.ui.text.input
                .TextFieldValue(""),
        )
    }

    MemoMenuHost(
        onEdit = { state ->
            val memo = state.memo as? com.lomo.domain.model.Memo
            if (memo != null) {
                editingMemo = memo
                inputText =
                    androidx.compose.ui.text.input
                        .TextFieldValue(
                            memo.content,
                            androidx.compose.ui.text
                                .TextRange(memo.content.length),
                        )
                showInputSheet = true
            }
        },
        onDelete = { state ->
            val memo = state.memo as? com.lomo.domain.model.Memo
            if (memo != null) {
                // 1. Add to deleting set to trigger fade out
                deletingIds.add(memo.id)
                // 2. Wait for animation then delete
                scope.launch {
                    delay(550) // DurationLong2
                    viewModel.deleteMemo(memo)
                    deletingIds.remove(memo.id)
                }
            }
        },
        onShare = { state ->
            com.lomo.app.util.ShareUtils.shareMemoText(
                context = context,
                content = state.content,
            )
        },
        onLanShare = { state ->
            val memo = state.memo as? com.lomo.domain.model.Memo
            if (memo != null) {
                onNavigateToShare(memo.content, memo.timestamp)
            }
        },
    ) { showMenu ->

        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                LargeTopAppBar(
                    title = {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Tag,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(tagName)
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                haptic.medium()
                                onBackClick()
                            },
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
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
        ) { padding ->
            val refreshState = pagedMemos.loadState.refresh

            when {
                refreshState is LoadState.Loading && pagedMemos.itemCount == 0 -> {
                    LazyColumn(
                        contentPadding =
                            PaddingValues(
                                top = padding.calculateTopPadding() + 16.dp,
                                start = 16.dp,
                                end = 16.dp,
                                bottom = 16.dp,
                            ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(5) { SkeletonMemoItem() }
                    }
                }

                refreshState is LoadState.Error && pagedMemos.itemCount == 0 -> {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(padding),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        Text(
                            text = refreshState.error.message ?: "Error loading memos",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                else -> {
                    if (pagedMemos.itemCount == 0) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(padding),
                        ) {
                            EmptyState(
                                icon = Icons.Outlined.Tag,
                                title = "No memos with #$tagName",
                                description = "Try adding this tag to some memos",
                            )
                        }
                    } else {
                        LazyColumn(
                            contentPadding =
                                PaddingValues(
                                    top = padding.calculateTopPadding() + 16.dp,
                                    start = 16.dp,
                                    end = 16.dp,
                                    bottom = 16.dp,
                                ),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(
                                count = pagedMemos.itemCount,
                                key = pagedMemos.itemKey { it.id },
                                contentType = { "memo" },
                            ) { index ->
                                val memo = pagedMemos[index]
                                if (memo != null) {
                                    val rootDir by viewModel.rootDir.collectAsStateWithLifecycle()
                                    val imageDir by viewModel.imageDir.collectAsStateWithLifecycle()
                                    val imageMap by viewModel.imageMap.collectAsStateWithLifecycle()
                                    val mutations by viewModel.pendingMutations.collectAsStateWithLifecycle()

                                    val mutation = mutations[memo.id]
                                    val isDeleting = deletingIds.contains(memo.id) || mutation is TagFilterViewModel.Mutation.Delete
                                    val isVisible = !(mutation is TagFilterViewModel.Mutation.Delete && mutation.isHidden)

                                    if (!isVisible) {
                                        Box(Modifier.size(0.dp))
                                    } else {
                                        // On-demand Mapping with caching
                                        val uiModel =
                                            remember(memo.id, memo.content, isDeleting, rootDir, imageDir, imageMap) {
                                                viewModel.mapper.mapToUiModel(
                                                    memo = memo,
                                                    rootPath = rootDir,
                                                    imagePath = imageDir,
                                                    imageMap = imageMap,
                                                    isDeleting = isDeleting,
                                                )
                                            }

                                        val alpha by animateFloatAsState(
                                            targetValue = if (isDeleting) 0f else 1f,
                                            animationSpec =
                                                tween(
                                                    durationMillis = com.lomo.ui.theme.MotionTokens.DurationLong2,
                                                    easing = com.lomo.ui.theme.MotionTokens.EasingEmphasizedAccelerate,
                                                ),
                                            label = "TagFilterItemDeleteAlpha",
                                        )

                                        Box(
                                            modifier =
                                                Modifier
                                                    .animateItem(
                                                        fadeInSpec =
                                                            keyframes {
                                                                durationMillis = 1000
                                                                0f at 0
                                                                0f at com.lomo.ui.theme.MotionTokens.DurationLong2
                                                                1f at 1000 using com.lomo.ui.theme.MotionTokens.EasingEmphasizedDecelerate
                                                            },
                                                        fadeOutSpec = snap(),
                                                        placementSpec =
                                                            spring<IntOffset>(
                                                                stiffness = Spring.StiffnessMediumLow,
                                                            ),
                                                    ).alpha(alpha),
                                        ) {
                                            MemoCard(
                                                content = memo.content,
                                                processedContent = uiModel.processedContent,
                                                precomputedNode = uiModel.markdownNode,
                                                timestamp = memo.timestamp,
                                                dateFormat = dateFormat,
                                                timeFormat = timeFormat,
                                                tags = uiModel.tags,
                                                onImageClick = onNavigateToImage,
                                                onMenuClick = {
                                                    showMenu(
                                                        com.lomo.ui.component.menu.MemoMenuState(
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
                        }
                    }
                }
            }
        }

        if (showInputSheet) {
            com.lomo.ui.component.input.InputSheet(
                inputValue = inputText,
                onInputValueChange = { inputText = it },
                onDismiss = {
                    showInputSheet = false
                    inputText =
                        androidx.compose.ui.text.input
                            .TextFieldValue("")
                    editingMemo = null
                },
                onSubmit = { content ->
                    editingMemo?.let { viewModel.updateMemo(it, content) }
                    showInputSheet = false
                    inputText =
                        androidx.compose.ui.text.input
                            .TextFieldValue("")
                    editingMemo = null
                },
                onImageClick = { },
                availableTags = emptyList(),
            )
        }
    }
}
