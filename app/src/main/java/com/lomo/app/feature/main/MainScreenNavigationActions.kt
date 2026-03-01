package com.lomo.app.feature.main

import androidx.compose.material3.DrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
    onNavigateToImage: (String) -> Unit,
    onNavigateToDailyReview: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onClearSidebarFilters: () -> Unit,
    onClearSelectedTag: () -> Unit,
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
            onClearSelectedTag = onClearSelectedTag,
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
    onNavigateToImage: (String) -> Unit,
    onNavigateToDailyReview: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onClearSidebarFilters: () -> Unit,
    onClearSelectedTag: () -> Unit,
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
        onClearSelectedTag,
        onOpenCreateMemo,
        onRefreshMemos,
        onRefreshingChange,
    ) {
        MainScreenActions(
            onSettings = {
                if (!isExpanded) scope.launch { drawerState.close() }
                onNavigateToSettings()
            },
            onTrash = {
                if (!isExpanded) scope.launch { drawerState.close() }
                onNavigateToTrash()
            },
            onSearch = onNavigateToSearch,
            onSidebarMemoClick = {
                onClearSidebarFilters()
                if (!isExpanded) scope.launch { drawerState.close() }
            },
            onSidebarTagClick = { tag ->
                if (!isExpanded) scope.launch { drawerState.close() }
                onNavigateToTag(tag)
            },
            onNavigateToImage = onNavigateToImage,
            onClearFilter = onClearSelectedTag,
            onMenuOpen = { scope.launch { drawerState.open() } },
            onFabClick = {
                haptic.longPress()
                if (canCreateMemo) {
                    onOpenCreateMemo()
                } else {
                    onNavigateToSettings()
                }
            },
            onRefresh = {
                scope.launch {
                    onRefreshingChange(true)
                    try {
                        onRefreshMemos()
                        delay(REFRESH_DELAY)
                    } finally {
                        onRefreshingChange(false)
                    }
                }
            },
            onDailyReviewClick = {
                if (!isExpanded) scope.launch { drawerState.close() }
                onNavigateToDailyReview()
            },
            onGalleryClick = {
                if (!isExpanded) scope.launch { drawerState.close() }
                onNavigateToGallery()
            },
        )
    }

data class MainScreenActions(
    val onSettings: () -> Unit,
    val onTrash: () -> Unit,
    val onSearch: () -> Unit,
    val onSidebarMemoClick: () -> Unit,
    val onSidebarTagClick: (String) -> Unit,
    val onClearFilter: () -> Unit,
    val onMenuOpen: () -> Unit,
    val onFabClick: () -> Unit,
    val onRefresh: () -> Unit,
    val onNavigateToImage: (String) -> Unit,
    val onDailyReviewClick: () -> Unit,
    val onGalleryClick: () -> Unit,
)

private const val REFRESH_DELAY = 500L
