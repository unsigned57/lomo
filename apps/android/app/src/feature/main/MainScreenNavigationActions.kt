package com.lomo.app.feature.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun MainScreenNavigationActionHost(
    scope: CoroutineScope,
    sidebar: MainSidebarState,
    canCreateMemo: Boolean,
    onCreateMemoUnavailable: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToTrash: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToTag: (String) -> Unit,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
    onNavigateToDailyReview: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onNavigateToStatistics: () -> Unit,
    onClearMainFilters: () -> Unit,
    onOpenMemoFilterPanel: () -> Unit,
    onOpenCreateMemo: () -> Unit,
    onOpenLongText: () -> Unit,
    onStartRecording: () -> Unit,
    onRefreshMemos: suspend () -> Unit,
    content: @Composable (MainScreenActions) -> Unit,
) {
    val haptic = LocalAppHapticFeedback.current
    val actions =
        rememberMainScreenActions(
            scope = scope,
            sidebar = sidebar,
            haptic = haptic,
            canCreateMemo = canCreateMemo,
            onCreateMemoUnavailable = onCreateMemoUnavailable,
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToTrash = onNavigateToTrash,
            onNavigateToSearch = onNavigateToSearch,
            onNavigateToTag = onNavigateToTag,
            onNavigateToImage = onNavigateToImage,
            onNavigateToDailyReview = onNavigateToDailyReview,
            onNavigateToGallery = onNavigateToGallery,
            onNavigateToStatistics = onNavigateToStatistics,
            onClearMainFilters = onClearMainFilters,
            onOpenMemoFilterPanel = onOpenMemoFilterPanel,
            onOpenCreateMemo = onOpenCreateMemo,
            onOpenLongText = onOpenLongText,
            onStartRecording = onStartRecording,
            onRefreshMemos = onRefreshMemos,
        )
    content(actions)
}

@Composable
internal fun rememberMainScreenActions(
    scope: CoroutineScope,
    sidebar: MainSidebarState,
    haptic: com.lomo.ui.util.AppHapticFeedback,
    canCreateMemo: Boolean,
    onCreateMemoUnavailable: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToTrash: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToTag: (String) -> Unit,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
    onNavigateToDailyReview: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onNavigateToStatistics: () -> Unit,
    onClearMainFilters: () -> Unit,
    onOpenMemoFilterPanel: () -> Unit,
    onOpenCreateMemo: () -> Unit,
    onOpenLongText: () -> Unit,
    onStartRecording: () -> Unit,
    onRefreshMemos: suspend () -> Unit,
): MainScreenActions = remember(
    scope,
    sidebar,
    haptic,
    canCreateMemo,
    onCreateMemoUnavailable,
    onNavigateToSettings,
    onNavigateToTrash,
    onNavigateToSearch,
    onNavigateToTag,
    onNavigateToImage,
    onNavigateToDailyReview,
    onNavigateToGallery,
    onNavigateToStatistics,
    onClearMainFilters,
    onOpenMemoFilterPanel,
    onOpenCreateMemo,
    onOpenLongText,
    onStartRecording,
    onRefreshMemos,
) {
    val closeDrawerIfNeeded: () -> Unit = { scope.launch { sidebar.closeOverlay() } }
    MainScreenActions(
        onSettings = closeDrawerNavigationAction(closeDrawerIfNeeded, onNavigateToSettings),
        onTrash = closeDrawerNavigationAction(closeDrawerIfNeeded, onNavigateToTrash),
        onSearch = onNavigateToSearch,
        onSidebarTagClick = { tag ->
            closeDrawerIfNeeded()
            onNavigateToTag(tag)
        },
        onNavigateToImage = onNavigateToImage,
        onClearFilter = onClearMainFilters,
        onOpenMemoFilterPanel = onOpenMemoFilterPanel,
        onMenuToggle = { scope.launch { sidebar.toggle() } },
        onFabClick =
        createMemoAction(
            haptic = haptic,
            canCreateMemo = canCreateMemo,
            onOpenCreateMemo = onOpenCreateMemo,
            onCreateMemoUnavailable = onCreateMemoUnavailable,
        ),
        onLongText = createMemoAction(
            haptic = haptic,
            canCreateMemo = canCreateMemo,
            onOpenCreateMemo = onOpenLongText,
            onCreateMemoUnavailable = onCreateMemoUnavailable,
        ),
        onRecord = createMemoAction(
            haptic = haptic,
            canCreateMemo = canCreateMemo,
            onOpenCreateMemo = onStartRecording,
            onCreateMemoUnavailable = onCreateMemoUnavailable,
        ),
        onRefresh =
        createRefreshAction(
            scope = scope,
            onRefreshMemos = onRefreshMemos,
        ),
        onDailyReviewClick = closeDrawerNavigationAction(closeDrawerIfNeeded, onNavigateToDailyReview),
        onGalleryClick = closeDrawerNavigationAction(closeDrawerIfNeeded, onNavigateToGallery),
        onStatisticsClick = closeDrawerNavigationAction(closeDrawerIfNeeded, onNavigateToStatistics),
    )
}

private fun closeDrawerNavigationAction(closeDrawerIfNeeded: () -> Unit, onNavigate: () -> Unit): () -> Unit = {
    closeDrawerIfNeeded()
    onNavigate()
}

private fun createMemoAction(
    haptic: com.lomo.ui.util.AppHapticFeedback,
    canCreateMemo: Boolean,
    onOpenCreateMemo: () -> Unit,
    onCreateMemoUnavailable: () -> Unit,
): () -> Unit = {
    haptic.longPress()
    if (canCreateMemo) {
        onOpenCreateMemo()
    } else {
        onCreateMemoUnavailable()
    }
}

private fun createRefreshAction(scope: CoroutineScope, onRefreshMemos: suspend () -> Unit): () -> Unit = {
    scope.launch { onRefreshMemos() }
}

data class MainScreenActions(
    val onSettings: () -> Unit,
    val onTrash: () -> Unit,
    val onSearch: () -> Unit,
    val onSidebarTagClick: (String) -> Unit,
    val onClearFilter: () -> Unit,
    val onOpenMemoFilterPanel: () -> Unit,
    val onMenuToggle: () -> Unit,
    val onFabClick: () -> Unit,
    val onLongText: () -> Unit,
    val onRecord: () -> Unit,
    val onRefresh: () -> Unit,
    val onNavigateToImage: (ImageViewerRequest) -> Unit,
    val onDailyReviewClick: () -> Unit,
    val onGalleryClick: () -> Unit,
    val onStatisticsClick: () -> Unit,
)
