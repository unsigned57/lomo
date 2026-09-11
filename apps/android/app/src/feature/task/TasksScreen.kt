package com.lomo.app.feature.task

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.app.util.injectedKoinViewModel
import com.lomo.domain.model.MemoTask
import com.lomo.ui.component.common.ExpressiveContainedLoadingIndicator
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.util.LocalAppHapticFeedback

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TasksViewModel = injectedKoinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val haptic = LocalAppHapticFeedback.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    LaunchedEffect(viewModel) {
        viewModel.ensureLoaded()
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sidebar_tasks)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            haptic.medium()
                            onBackClick()
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when (val state = uiState) {
                is TasksScreenState.Loading -> ExpressiveContainedLoadingIndicator()
                is TasksScreenState.Error -> {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(AppSpacing.Medium),
                    )
                }
                is TasksScreenState.Ready -> {
                    if (state.tasks.isEmpty()) {
                        Text(
                            text = stringResource(R.string.tasks_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        TasksList(
                            tasks = state.tasks,
                            onToggle = viewModel::toggleTask,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TasksList(
    tasks: List<MemoTask>,
    onToggle: (MemoTask) -> Unit,
) {
    val haptic = LocalAppHapticFeedback.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(AppSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        items(
            items = tasks,
            key = { task -> "${task.memoId}:${task.lineIndex}" },
        ) { task ->
            TaskRow(
                task = task,
                onToggle = {
                    haptic.light()
                    onToggle(task)
                },
            )
        }
    }
}

@Composable
private fun TaskRow(
    task: MemoTask,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = AppSpacing.ExtraSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        Checkbox(
            checked = task.done,
            onCheckedChange = { onToggle() },
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .clickable(onClick = onToggle),
        ) {
            Text(
                text = task.text,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (task.done) TextDecoration.LineThrough else null,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = task.sourcePath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
