package com.lomo.app.feature.main

import androidx.compose.material3.DrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MainScreenNavigationActionHost(
    scope: CoroutineScope,
    drawerState: DrawerState,
    isExpanded: Boolean,
    canCreateMemo: Boolean,
    onNavigateToSettings: () -> Unit,
    onNavigateToTrash: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToTag: (String) -> Unit,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
    onNavigateToDailyReview: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onClearSidebarFilters: () -> Unit,
    onClearMainFilters: () -> Unit,
    onOpenMemoFilterPanel: () -> Unit,
    onOpenCreateMemo: () -> Unit,
    onRefreshMemos: suspend () -> Unit,
    onRefreshingChange: (Boolean) -> Unit,
    content: @Composable (MainScreenActions) -> Unit,
) {
    val haptic = LocalAppHapticFeedback.current
    val actions =
        rememberMainScreenActions(
            scope = scope,
            drawerState = drawerState,
            haptic = haptic,
            isExpanded = isExpanded,
            canCreateMemo = canCreateMemo,
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToTrash = onNavigateToTrash,
            onNavigateToSearch = onNavigateToSearch,
            onNavigateToTag = onNavigateToTag,
            onNavigateToImage = onNavigateToImage,
            onNavigateToDailyReview = onNavigateToDailyReview,
            onNavigateToGallery = onNavigateToGallery,
            onClearSidebarFilters = onClearSidebarFilters,
            onClearMainFilters = onClearMainFilters,
            onOpenMemoFilterPanel = onOpenMemoFilterPanel,
            onOpenCreateMemo = onOpenCreateMemo,
            onRefreshMemos = onRefreshMemos,
            onRefreshingChange = onRefreshingChange,
        )
    content(actions)
}

@Composable
fun rememberMainScreenActions(
    scope: CoroutineScope,
    drawerState: DrawerState,
    haptic: com.lomo.ui.util.AppHapticFeedback,
    isExpanded: Boolean,
    canCreateMemo: Boolean,
    onNavigateToSettings: () -> Unit,
    onNavigateToTrash: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToTag: (String) -> Unit,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
    onNavigateToDailyReview: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onClearSidebarFilters: () -> Unit,
    onClearMainFilters: () -> Unit,
    onOpenMemoFilterPanel: () -> Unit,
    onOpenCreateMemo: () -> Unit,
    onRefreshMemos: suspend () -> Unit,
    onRefreshingChange: (Boolean) -> Unit,
): MainScreenActions =
    remember(
        scope,
        drawerState,
        haptic,
        isExpanded,
        canCreateMemo,
        onNavigateToSettings,
        onNavigateToTrash,
        onNavigateToSearch,
        onNavigateToTag,
        onNavigateToImage,
        onNavigateToDailyReview,
        onNavigateToGallery,
        onClearSidebarFilters,
        onClearMainFilters,
        onOpenMemoFilterPanel,
        onOpenCreateMemo,
        onRefreshMemos,
        onRefreshingChange,
    ) {
        val closeDrawerIfNeeded = {
            if (!isExpanded) {
                scope.launch { drawerState.close() }
            }
        }
        MainScreenActions(
            onSettings = closeDrawerNavigationAction(closeDrawerIfNeeded, onNavigateToSettings),
            onTrash = closeDrawerNavigationAction(closeDrawerIfNeeded, onNavigateToTrash),
            onSearch = onNavigateToSearch,
            onSidebarMemoClick = {
                onClearSidebarFilters()
                closeDrawerIfNeeded()
            },
            onSidebarTagClick = { tag ->
                closeDrawerIfNeeded()
                onNavigateToTag(tag)
            },
            onNavigateToImage = onNavigateToImage,
            onClearFilter = onClearMainFilters,
            onOpenMemoFilterPanel = onOpenMemoFilterPanel,
            onMenuOpen = { scope.launch { drawerState.open() } },
            onFabClick =
                createFabAction(
                    haptic = haptic,
                    canCreateMemo = canCreateMemo,
                    onOpenCreateMemo = onOpenCreateMemo,
                    onNavigateToSettings = onNavigateToSettings,
                ),
            onRefresh =
                createRefreshAction(
                    scope = scope,
                    onRefreshMemos = onRefreshMemos,
                    onRefreshingChange = onRefreshingChange,
                ),
            onDailyReviewClick = closeDrawerNavigationAction(closeDrawerIfNeeded, onNavigateToDailyReview),
            onGalleryClick = closeDrawerNavigationAction(closeDrawerIfNeeded, onNavigateToGallery),
        )
    }

private fun closeDrawerNavigationAction(
    closeDrawerIfNeeded: () -> Unit,
    onNavigate: () -> Unit,
): () -> Unit =
    {
        closeDrawerIfNeeded()
        onNavigate()
    }

private fun createFabAction(
    haptic: com.lomo.ui.util.AppHapticFeedback,
    canCreateMemo: Boolean,
    onOpenCreateMemo: () -> Unit,
    onNavigateToSettings: () -> Unit,
): () -> Unit =
    {
        haptic.longPress()
        if (canCreateMemo) {
            onOpenCreateMemo()
        } else {
            onNavigateToSettings()
        }
    }

private fun createRefreshAction(
    scope: CoroutineScope,
    onRefreshMemos: suspend () -> Unit,
    onRefreshingChange: (Boolean) -> Unit,
): () -> Unit =
    {
        scope.launch {
            onRefreshingChange(true)
            try {
                onRefreshMemos()
                delay(REFRESH_DELAY)
            } finally {
                onRefreshingChange(false)
            }
        }
    }

data class MainScreenActions(
    val onSettings: () -> Unit,
    val onTrash: () -> Unit,
    val onSearch: () -> Unit,
    val onSidebarMemoClick: () -> Unit,
    val onSidebarTagClick: (String) -> Unit,
    val onClearFilter: () -> Unit,
    val onOpenMemoFilterPanel: () -> Unit,
    val onMenuOpen: () -> Unit,
    val onFabClick: () -> Unit,
    val onRefresh: () -> Unit,
    val onNavigateToImage: (ImageViewerRequest) -> Unit,
    val onDailyReviewClick: () -> Unit,
    val onGalleryClick: () -> Unit,
)

private const val REFRESH_DELAY = 500L
