package com.lomo.app.feature.tag

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.feature.memo.MemoCardList
import com.lomo.app.feature.memo.MemoCardListAnimation
import com.lomo.app.feature.memo.MemoEditorSheetHost
import com.lomo.app.feature.memo.MemoMenuBinder
import com.lomo.app.feature.memo.rememberMemoEditorController
import com.lomo.ui.component.common.EmptyState

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TagFilterScreen(
    tagName: String,
    onBackClick: () -> Unit,
    onNavigateToImage: (String) -> Unit,
    onNavigateToShare: (String, Long) -> Unit = { _, _ -> },
    viewModel: TagFilterViewModel = hiltViewModel(),
) {
    val memos by viewModel.uiMemos.collectAsStateWithLifecycle()
    val appPreferences by viewModel.appPreferences.collectAsStateWithLifecycle()
    val dateFormat = appPreferences.dateFormat
    val timeFormat = appPreferences.timeFormat
    val shareCardStyle = appPreferences.shareCardStyle
    val shareCardShowTime = appPreferences.shareCardShowTime
    val doubleTapEditEnabled = appPreferences.doubleTapEditEnabled
    val activeDayCount by viewModel.activeDayCount.collectAsStateWithLifecycle()
    val imageDirectory by viewModel.imageDir.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    val editorController = rememberMemoEditorController()

    MemoMenuBinder(
        shareCardStyle = shareCardStyle,
        shareCardShowTime = shareCardShowTime,
        activeDayCount = activeDayCount,
        onEditMemo = editorController::openForEdit,
        onDeleteMemo = viewModel::deleteMemo,
        onLanShare = onNavigateToShare,
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
            if (memos.isEmpty()) {
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
                MemoCardList(
                    memos = memos,
                    dateFormat = dateFormat,
                    timeFormat = timeFormat,
                    doubleTapEditEnabled = doubleTapEditEnabled,
                    onMemoEdit = editorController::openForEdit,
                    onShowMenu = showMenu,
                    onImageClick = onNavigateToImage,
                    animation = MemoCardListAnimation.Placement,
                    contentPadding =
                        PaddingValues(
                            top = padding.calculateTopPadding() + 16.dp,
                            start = 16.dp,
                            end = 16.dp,
                            bottom = 16.dp,
                        ),
                )
            }
        }

        MemoEditorSheetHost(
            controller = editorController,
            imageDirectory = imageDirectory,
            onSaveImage = viewModel::saveImage,
            onSubmit = viewModel::updateMemo,
            availableTags = emptyList(),
        )
    }
}
