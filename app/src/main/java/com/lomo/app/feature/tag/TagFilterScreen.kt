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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.memo.MemoCardList
import com.lomo.app.feature.memo.MemoCardListAnimation
import com.lomo.app.feature.memo.MemoInteractionHost
import com.lomo.ui.component.common.EmptyState

private val TAG_FILTER_ICON_SIZE = 28.dp
private val TAG_FILTER_ICON_SPACING = 8.dp
private val TAG_FILTER_LIST_PADDING = 16.dp
private val TAG_FILTER_LIST_BOTTOM_PADDING = 88.dp

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TagFilterScreen(
    tagName: String,
    onBackClick: () -> Unit,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
    onNavigateToShare: (String, Long) -> Unit = { _, _ -> },
    viewModel: TagFilterViewModel = hiltViewModel(),
) {
    val memos by viewModel.uiMemos.collectAsStateWithLifecycle()
    val appPreferences by viewModel.appPreferences.collectAsStateWithLifecycle()
    val activeDayCount by viewModel.activeDayCount.collectAsStateWithLifecycle()
    val imageDirectory by viewModel.imageDir.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current

    TagFilterScreenEffects(
        errorMessage = errorMessage,
        snackbarHostState = snackbarHostState,
        onClearError = viewModel::clearError,
    )

    MemoInteractionHost(
        shareCardShowTime = appPreferences.shareCardShowTime,
        activeDayCount = activeDayCount,
        onDeleteMemo = viewModel::deleteMemo,
        onUpdateMemo = viewModel::updateMemo,
        onSaveImage = viewModel::saveImage,
        imageDirectory = imageDirectory,
        onLanShare = onNavigateToShare,
    ) { showMenu, openEditor ->
        TagFilterScreenScaffold(
            tagName = tagName,
            snackbarHostState = snackbarHostState,
            onBackClick = {
                haptic.medium()
                onBackClick()
            },
        ) { padding ->
            TagFilterScreenContent(
                tagName = tagName,
                memos = memos,
                dateFormat = appPreferences.dateFormat,
                timeFormat = appPreferences.timeFormat,
                doubleTapEditEnabled = appPreferences.doubleTapEditEnabled,
                freeTextCopyEnabled = appPreferences.freeTextCopyEnabled,
                onMemoEdit = openEditor,
                onShowMenu = showMenu,
                onImageClick = onNavigateToImage,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun TagFilterScreenEffects(
    errorMessage: String?,
    snackbarHostState: SnackbarHostState,
    onClearError: () -> Unit,
) {
    LaunchedEffect(errorMessage) {
        errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            onClearError()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagFilterScreenScaffold(
    tagName: String,
    snackbarHostState: SnackbarHostState,
    onBackClick: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeTopAppBar(
                title = { TagFilterTitle(tagName = tagName) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.cd_close),
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
        content = content,
    )
}

@Composable
private fun TagFilterTitle(tagName: String) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.Tag,
            contentDescription = null,
            modifier = Modifier.size(TAG_FILTER_ICON_SIZE),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(TAG_FILTER_ICON_SPACING))
        Text(tagName)
    }
}

@Composable
private fun TagFilterScreenContent(
    tagName: String,
    memos: List<com.lomo.app.feature.main.MemoUiModel>,
    dateFormat: String,
    timeFormat: String,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    onMemoEdit: (com.lomo.domain.model.Memo) -> Unit,
    onShowMenu: (com.lomo.ui.component.menu.MemoMenuState) -> Unit,
    onImageClick: (ImageViewerRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (memos.isEmpty()) {
        Box(modifier = modifier.fillMaxSize()) {
            EmptyState(
                icon = Icons.Outlined.Tag,
                title = stringResource(R.string.empty_no_tag_matches_title, tagName),
                description = stringResource(R.string.empty_no_tag_matches_subtitle),
            )
        }
        return
    }

    MemoCardList(
        memos = memos,
        dateFormat = dateFormat,
        timeFormat = timeFormat,
        doubleTapEditEnabled = doubleTapEditEnabled,
        freeTextCopyEnabled = freeTextCopyEnabled,
        onMemoEdit = onMemoEdit,
        onShowMenu = onShowMenu,
        onImageClick = onImageClick,
        animation = MemoCardListAnimation.Placement,
        contentPadding =
            PaddingValues(
                top = TAG_FILTER_LIST_PADDING,
                start = TAG_FILTER_LIST_PADDING,
                end = TAG_FILTER_LIST_PADDING,
                bottom = TAG_FILTER_LIST_BOTTOM_PADDING,
            ),
        modifier = modifier.fillMaxSize(),
    )
}
