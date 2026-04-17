package com.lomo.app.feature.gallery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.request.ImageRequest
import com.lomo.app.R
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.image.createImageViewerRequest
import com.lomo.app.feature.main.MainViewModel
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.util.activityHiltViewModel
import com.lomo.ui.component.common.EmptyState
import com.lomo.ui.theme.AppSpacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

private data class GalleryImageItem(
    val imageUrl: String,
    val allImageUrls: ImmutableList<String>,
)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    onBackClick: () -> Unit,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
) {
    val viewModel: MainViewModel = activityHiltViewModel()
    val memos by viewModel.galleryUiMemos.collectAsStateWithLifecycle()
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

    GalleryScreenScaffold(
        onBackClick = onBackClick,
        haptic = haptic,
        scrollBehavior = scrollBehavior,
        snackbarHostState = snackbarHostState,
    ) { padding ->
        GalleryScreenContent(
            memos = remember(memos) { memos.toImmutableList() },
            padding = padding,
            onNavigateToImage = onNavigateToImage,
        )
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
    memos: ImmutableList<MemoUiModel>,
    padding: PaddingValues,
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

    val imageItems =
        remember(memos) {
            memos.flatMap { uiModel ->
                uiModel.imageUrls.map { url ->
                    GalleryImageItem(
                        imageUrl = url,
                        allImageUrls = uiModel.imageUrls,
                    )
                }
            }
        }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding =
            PaddingValues(
                top = padding.calculateTopPadding() + AppSpacing.Small,
                start = AppSpacing.Small,
                end = AppSpacing.Small,
                bottom = AppSpacing.Medium,
            ),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(
            items = imageItems,
            key = { it.imageUrl },
            contentType = { "image" },
        ) { item ->
            GalleryImageThumbnail(
                imageUrl = item.imageUrl,
                onClick = {
                    onNavigateToImage(
                        createImageViewerRequest(
                            imageUrls = item.allImageUrls,
                            clickedUrl = item.imageUrl,
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun GalleryImageThumbnail(
    imageUrl: String,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val model =
        remember(imageUrl, context) {
            ImageRequest.Builder(context).data(imageUrl).build()
        }

    val painter = coil3.compose.rememberAsyncImagePainter(model)

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
