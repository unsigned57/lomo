package com.lomo.app.feature.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.PhotoLibrary
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.main.MainViewModel
import com.lomo.app.feature.memo.MemoCardList
import com.lomo.app.feature.memo.MemoCardListAnimation
import com.lomo.app.feature.memo.MemoMenuBinder
import com.lomo.app.util.activityHiltViewModel
import com.lomo.domain.model.Memo
import com.lomo.ui.component.common.EmptyState
import com.lomo.ui.component.menu.memoAs
import com.lomo.ui.theme.AppSpacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    onBackClick: () -> Unit,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
    onNavigateToShare: (String, Long) -> Unit = { _, _ -> },
) {
    val viewModel: MainViewModel = activityHiltViewModel()
    val memos by viewModel.galleryUiMemos.collectAsStateWithLifecycle()
    val appPreferences by viewModel.appPreferences.collectAsStateWithLifecycle()
    val shareCardShowTime = appPreferences.shareCardShowTime
    val shareCardShowSignature = appPreferences.shareCardShowBrand
    val shareCardSignatureText = appPreferences.shareCardSignatureText
    val dateFormat = appPreferences.dateFormat
    val timeFormat = appPreferences.timeFormat
    val doubleTapEditEnabled = appPreferences.doubleTapEditEnabled
    val freeTextCopyEnabled = appPreferences.freeTextCopyEnabled
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current

    GalleryScreenEffects(
        onSyncImageCacheNow = viewModel.syncImageCacheNow,
        errorMessage = errorMessage,
        snackbarHostState = snackbarHostState,
        onClearError = viewModel.clearError,
    )

    MemoMenuBinder(
        shareCardShowTime = shareCardShowTime,
        shareCardShowSignature = shareCardShowSignature,
        shareCardSignatureText = shareCardSignatureText,
        onEditMemo = { memo ->
            viewModel.requestOpenMemo(memo.id)
            onBackClick()
        },
        onDeleteMemo = viewModel.deleteMemo,
        onLanShare = onNavigateToShare,
        onJump = { state ->
            state.memoAs<Memo>()?.let { memo ->
                viewModel.requestFocusMemo(memo.id)
                onBackClick()
            }
        },
        showJump = true,
        memoActionAutoReorderEnabled = appPreferences.memoActionAutoReorderEnabled,
        memoActionOrder = appPreferences.memoActionOrder,
        onMemoActionInvoked = viewModel::recordMemoActionUsage,
    ) { showMenu ->
        GalleryScreenScaffold(
            onBackClick = onBackClick,
            haptic = haptic,
            scrollBehavior = scrollBehavior,
            snackbarHostState = snackbarHostState,
        ) { padding ->
            GalleryScreenContent(
                memos = remember(memos) { memos.toImmutableList() },
                dateFormat = dateFormat,
                timeFormat = timeFormat,
                doubleTapEditEnabled = doubleTapEditEnabled,
                freeTextCopyEnabled = freeTextCopyEnabled,
                padding = padding,
                onEditMemo = { memo ->
                    viewModel.requestOpenMemo(memo.id)
                    onBackClick()
                },
                onShowMenu = showMenu,
                onNavigateToImage = onNavigateToImage,
            )
        }
    }
}

@Composable
private fun GalleryScreenEffects(
    onSyncImageCacheNow: () -> Unit,
    errorMessage: String?,
    snackbarHostState: SnackbarHostState,
    onClearError: () -> Unit,
) {
    LaunchedEffect(Unit) {
        onSyncImageCacheNow()
    }
    LaunchedEffect(errorMessage) {
        errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            onClearError()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryScreenScaffold(
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
                title = { Text(stringResource(R.string.gallery_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            haptic.medium()
                            onBackClick()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
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
        content = content,
    )
}

@Composable
private fun GalleryScreenContent(
    memos: ImmutableList<com.lomo.app.feature.main.MemoUiModel>,
    dateFormat: String,
    timeFormat: String,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    padding: PaddingValues,
    onEditMemo: (Memo) -> Unit,
    onShowMenu: (com.lomo.ui.component.menu.MemoMenuState) -> Unit,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
) {
    if (memos.isEmpty()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            EmptyState(
                icon = Icons.Outlined.PhotoLibrary,
                title = stringResource(R.string.gallery_empty_title),
                description = stringResource(R.string.gallery_empty_desc),
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
        onMemoEdit = onEditMemo,
        onShowMenu = onShowMenu,
        onImageClick = onNavigateToImage,
        animation = MemoCardListAnimation.Placement,
        contentPadding =
            PaddingValues(
                top = padding.calculateTopPadding() + AppSpacing.Medium,
                start = AppSpacing.Medium,
                end = AppSpacing.Medium,
                bottom = AppSpacing.Medium,
            ),
    )
}
