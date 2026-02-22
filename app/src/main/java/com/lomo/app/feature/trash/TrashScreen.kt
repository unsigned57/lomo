package com.lomo.app.feature.trash

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.rounded.DeleteSweep
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.domain.model.Memo
import com.lomo.ui.component.card.MemoCard

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TrashScreen(
    onBackClick: () -> Unit,
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val trashMemos by viewModel.trashUiMemos.collectAsStateWithLifecycle()
    val dateFormat by viewModel.dateFormat.collectAsStateWithLifecycle()
    val timeFormat by viewModel.timeFormat.collectAsStateWithLifecycle()

    var selectedMemo by remember { mutableStateOf<Memo?>(null) }
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
                                ).fillMaxWidth(),
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
