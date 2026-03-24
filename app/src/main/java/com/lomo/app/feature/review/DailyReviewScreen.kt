package com.lomo.app.feature.review

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.app.feature.common.UiState
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.image.createImageViewerRequest
import com.lomo.app.feature.memo.MemoCardEntry
import com.lomo.app.feature.memo.MemoInteractionHost
import com.lomo.domain.model.Memo
import com.lomo.ui.component.common.EmptyState
import com.lomo.ui.component.common.ExpressiveContainedLoadingIndicator
import com.lomo.ui.component.menu.memoAs
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.util.LocalAppHapticFeedback

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyReviewScreen(
    onBackClick: () -> Unit,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
    onNavigateToShare: (String, Long) -> Unit = { _, _ -> },
    onNavigateToMemo: (String) -> Unit = {},
    viewModel: DailyReviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val appPreferences by viewModel.appPreferences.collectAsStateWithLifecycle()
    val dateFormat = appPreferences.dateFormat
    val timeFormat = appPreferences.timeFormat
    val shareCardShowTime = appPreferences.shareCardShowTime
    val doubleTapEditEnabled = appPreferences.doubleTapEditEnabled
    val freeTextCopyEnabled = appPreferences.freeTextCopyEnabled
    val activeDayCount by viewModel.activeDayCount.collectAsStateWithLifecycle()
    val imageDirectory by viewModel.imageDirectory.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val haptic = LocalAppHapticFeedback.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    MemoInteractionHost(
        shareCardShowTime = shareCardShowTime,
        activeDayCount = activeDayCount,
        onDeleteMemo = viewModel::deleteMemo,
        onUpdateMemo = viewModel::updateMemo,
        onSaveImage = viewModel::saveImage,
        imageDirectory = imageDirectory,
        onLanShare = onNavigateToShare,
        onJump = { state ->
            state.memoAs<Memo>()?.let { memo ->
                onNavigateToMemo(memo.id)
            }
        },
        showJump = true,
    ) { showMenu, openEditor ->
        DailyReviewScreenScaffold(
            onBackClick = onBackClick,
            haptic = haptic,
            scrollBehavior = scrollBehavior,
            snackbarHostState = snackbarHostState,
        ) { scaffoldPadding ->
            DailyReviewScreenContent(
                uiState = uiState,
                dateFormat = dateFormat,
                timeFormat = timeFormat,
                doubleTapEditEnabled = doubleTapEditEnabled,
                freeTextCopyEnabled = freeTextCopyEnabled,
                padding = scaffoldPadding,
                onShowMenu = showMenu,
                onOpenEditor = openEditor,
                onNavigateToImage = onNavigateToImage,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DailyReviewScreenScaffold(
    onBackClick: () -> Unit,
    haptic: com.lomo.ui.util.AppHapticFeedback,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    snackbarHostState: SnackbarHostState,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(androidx.compose.ui.res.stringResource(R.string.sidebar_daily_review)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            haptic.medium()
                            onBackClick()
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = androidx.compose.ui.res.stringResource(R.string.back),
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
        content = content,
    )
}

@Composable
private fun DailyReviewScreenContent(
    uiState: UiState<List<com.lomo.app.feature.main.MemoUiModel>>,
    dateFormat: String,
    timeFormat: String,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    padding: PaddingValues,
    onShowMenu: (com.lomo.ui.component.menu.MemoMenuState) -> Unit,
    onOpenEditor: (Memo) -> Unit,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        when (val state = uiState) {
            is UiState.Loading -> ExpressiveContainedLoadingIndicator()

            is UiState.Error -> {
                Text(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(AppSpacing.Medium),
                )
            }

            is UiState.Success -> {
                val memos = state.data
                if (memos.isEmpty()) {
                    EmptyState(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        title = androidx.compose.ui.res.stringResource(R.string.review_no_memos_title),
                        description = androidx.compose.ui.res.stringResource(R.string.review_no_memos_desc),
                    )
                } else {
                    DailyReviewPager(
                        memos = memos,
                        dateFormat = dateFormat,
                        timeFormat = timeFormat,
                        doubleTapEditEnabled = doubleTapEditEnabled,
                        freeTextCopyEnabled = freeTextCopyEnabled,
                        onShowMenu = onShowMenu,
                        onOpenEditor = onOpenEditor,
                        onNavigateToImage = onNavigateToImage,
                    )
                }
            }

            else -> Unit
        }
    }
}

@Composable
private fun DailyReviewPager(
    memos: List<com.lomo.app.feature.main.MemoUiModel>,
    dateFormat: String,
    timeFormat: String,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    onShowMenu: (com.lomo.ui.component.menu.MemoMenuState) -> Unit,
    onOpenEditor: (Memo) -> Unit,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { memos.size })

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = AppSpacing.Large),
            pageSpacing = AppSpacing.Medium,
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
        ) { page ->
            DailyReviewPagerPage(
                memo = memos[page],
                page = page,
                totalPages = memos.size,
                dateFormat = dateFormat,
                timeFormat = timeFormat,
                doubleTapEditEnabled = doubleTapEditEnabled,
                freeTextCopyEnabled = freeTextCopyEnabled,
                onShowMenu = onShowMenu,
                onOpenEditor = onOpenEditor,
                onNavigateToImage = onNavigateToImage,
            )
        }
    }
}

@Composable
private fun DailyReviewPagerPage(
    memo: com.lomo.app.feature.main.MemoUiModel,
    page: Int,
    totalPages: Int,
    dateFormat: String,
    timeFormat: String,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    onShowMenu: (com.lomo.ui.component.menu.MemoMenuState) -> Unit,
    onOpenEditor: (Memo) -> Unit,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
) {
    val onMemoImageClick =
        remember(memo.imageUrls, onNavigateToImage) {
            { url: String ->
                onNavigateToImage(
                    createImageViewerRequest(
                        imageUrls = memo.imageUrls,
                        clickedUrl = url,
                    ),
                )
            }
        }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = AppSpacing.ExtraLarge)
                    .verticalScroll(rememberScrollState()),
        ) {
            MemoCardEntry(
                uiModel = memo,
                dateFormat = dateFormat,
                timeFormat = timeFormat,
                doubleTapEditEnabled = doubleTapEditEnabled,
                freeTextCopyEnabled = freeTextCopyEnabled,
                onMemoEdit = onOpenEditor,
                onShowMenu = onShowMenu,
                onImageClick = onMemoImageClick,
            )

            Text(
                text = "${page + 1} / $totalPages",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = AppSpacing.Medium),
                textAlign = TextAlign.Center,
            )
        }
    }
}
