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
import com.lomo.app.feature.common.MemoActionOrderScopes
import com.lomo.app.feature.main.MainViewModel
import com.lomo.app.feature.memo.MemoMenuBinder
import com.lomo.app.feature.memo.handleMemoJumpToMain
import com.lomo.app.util.activityHiltViewModel
import com.lomo.ui.component.common.EmptyState
import com.lomo.ui.component.menu.MemoMenuState
import com.lomo.ui.theme.AppSpacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.collections.immutable.toPersistentMap

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    onBackClick: () -> Unit,
    onNavigateToReel: (memoId: String, imageIndex: Int, aspectByMemoId: Map<String, Float>) -> Unit,
    onNavigateToShare: (String, Long) -> Unit = { _, _ -> },
    onNavigateToMain: () -> Unit = onBackClick,
    lanShareEnabled: Boolean = true,
) {
    val viewModel: MainViewModel = activityHiltViewModel()
    val memos by viewModel.galleryUiMemos.collectAsStateWithLifecycle()
    val deletingMemoIds by viewModel.deletingMemoIds.collectAsStateWithLifecycle()
    val appPreferences by viewModel.appPreferences.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val dimensionResolver = remember { GalleryImageDimensionResolver() }
    val aspectByPath by dimensionResolver.aspectFlow.collectAsStateWithLifecycle()
    val aspectByMemoId =
        remember(memos, aspectByPath) {
            memos
                .mapNotNull { uiModel ->
                    val firstImageUrl = uiModel.imageUrls.firstOrNull() ?: return@mapNotNull null
                    uiModel.memo.id to (aspectByPath[firstImageUrl] ?: GALLERY_DEFAULT_ASPECT_RATIO)
                }.toMap()
                .toPersistentMap()
        }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current

    GalleryScreenEffects(
        onSyncImageCacheNow = viewModel.syncImageCacheNow,
        errorMessage = errorMessage,
        snackbarHostState = snackbarHostState,
        onClearError = viewModel.clearError,
        memos = remember(memos) { memos.toImmutableList() },
        deletingMemoIds = remember(deletingMemoIds) { deletingMemoIds.toImmutableSet() },
        onDeleteAnimationSettled = viewModel::onPagedDeleteAnimationSettled,
        dimensionResolver = dimensionResolver,
    )

    MemoMenuBinder(
        shareCardShowTime = appPreferences.shareCardShowTime,
        shareCardShowSignature = appPreferences.shareCardShowBrand,
        shareCardSignatureText = appPreferences.shareCardSignatureText,
        onEditMemo = { memo ->
            viewModel.requestOpenMemo(memo.id)
            onBackClick()
        },
        onDeleteMemo = viewModel.deleteMemo,
        onLanShare = if (lanShareEnabled) onNavigateToShare else null,
        onJump = { state ->
            handleMemoJumpToMain(
                state = state,
                requestFocusMemo = viewModel.requestFocusMemoInDefaultMainList,
                navigateToMain = onNavigateToMain,
            )
        },
        showJump = true,
        memoActionAutoReorderEnabled = appPreferences.memoActionAutoReorderEnabled,
        memoActionOrder = appPreferences.memoActionOrderFor(MemoActionOrderScopes.GALLERY),
        onMemoActionInvoked = viewModel.recordGalleryMemoActionUsage,
        onMemoActionOrderChanged = viewModel.updateGalleryMemoActionOrder,
    ) { showMenu ->
        GalleryScreenScaffold(
            onBackClick = onBackClick,
            haptic = haptic,
            scrollBehavior = scrollBehavior,
            snackbarHostState = snackbarHostState,
        ) { padding ->
            GalleryScreenContent(
                memos = remember(memos) { memos.toImmutableList() },
                aspectByMemoId = aspectByMemoId,
                dateFormat = appPreferences.dateFormat,
                timeFormat = appPreferences.timeFormat,
                padding = padding,
                onShowMenu = showMenu,
                onNavigateToReel = onNavigateToReel,
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
    memos: ImmutableList<com.lomo.app.feature.main.MemoUiModel>,
    deletingMemoIds: ImmutableSet<String>,
    onDeleteAnimationSettled: (String) -> Unit,
    dimensionResolver: GalleryImageDimensionResolver,
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
    LaunchedEffect(memos, dimensionResolver) {
        memos
            .asSequence()
            .mapNotNull { uiModel -> uiModel.imageUrls.firstOrNull() }
            .distinct()
            .forEach { imageUrl -> dimensionResolver.resolve(imageUrl) }
    }
    LaunchedEffect(memos, deletingMemoIds) {
        val visibleMemoIds = memos.asSequence().map { uiModel -> uiModel.memo.id }.toSet()
        deletingMemoIds
            .asSequence()
            .filterNot { memoId -> memoId in visibleMemoIds }
            .forEach(onDeleteAnimationSettled)
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
    aspectByMemoId: ImmutableMap<String, Float>,
    dateFormat: String,
    timeFormat: String,
    padding: PaddingValues,
    onShowMenu: (MemoMenuState) -> Unit,
    onNavigateToReel: (memoId: String, imageIndex: Int, aspectByMemoId: Map<String, Float>) -> Unit,
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

    GalleryGridContent(
        memos = memos,
        aspectByMemoId = aspectByMemoId,
        dateFormat = dateFormat,
        timeFormat = timeFormat,
        onShowMenu = onShowMenu,
        contentPadding =
            PaddingValues(
                top = padding.calculateTopPadding() + AppSpacing.Medium,
                start = AppSpacing.Medium,
                end = AppSpacing.Medium,
                bottom = AppSpacing.Medium,
            ),
        onCellClick = { memoId, imageIndex ->
            onNavigateToReel(memoId, imageIndex, aspectByMemoId)
        },
    )
}
