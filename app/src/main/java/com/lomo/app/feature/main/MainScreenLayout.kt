package com.lomo.app.feature.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoSortOption
import com.lomo.ui.component.menu.MemoMenuState
import com.lomo.ui.component.navigation.SidebarDrawer
import com.lomo.ui.theme.MotionTokens
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainScreenNavigationRender(
    screenState: MainScreenUiSnapshot,
    hostState: MainScreenHostState,
    viewModel: MainViewModel,
    actions: MainScreenActions,
    isRefreshing: Boolean,
    isMemoFilterSheetVisible: Boolean,
    onDismissMemoFilterSheet: () -> Unit,
    onHeatmapDateLongPress: (LocalDate) -> Unit,
    onScrollToTop: () -> Unit,
    onShowMemoMenu: (MemoMenuState) -> Unit,
    onOpenEditor: (Memo) -> Unit,
) {
    MainScreenRenderHost(
        isExpanded = hostState.isExpanded,
        drawerState = hostState.drawerState,
        sidebarUiState = screenState.sidebarUiState,
        actions = actions,
        snackbarHostState = hostState.snackbarHostState,
        scrollBehavior = hostState.scrollBehavior,
        selectedTag = screenState.selectedTag,
        searchQuery = screenState.searchQuery,
        memoListFilter = screenState.memoListFilter,
        isFilterActive = screenState.selectedTag != null || screenState.memoListFilter.hasDateRange,
        isMemoFilterSheetVisible = isMemoFilterSheetVisible,
        uiState = screenState.uiState,
        hasItems = screenState.hasItems,
        uiMemos = screenState.uiMemos,
        deletingMemoIds = viewModel.deletingMemoIds,
        listState = hostState.listState,
        isRefreshing = isRefreshing,
        onTodoClick = viewModel.updateMemo,
        dateFormat = screenState.dateFormat,
        timeFormat = screenState.timeFormat,
        onMemoDoubleClick = onOpenEditor,
        doubleTapEditEnabled = screenState.doubleTapEditEnabled,
        freeTextCopyEnabled = screenState.freeTextCopyEnabled,
        onShowMemoMenu = onShowMemoMenu,
        onMemoSortOptionSelected = viewModel.updateMemoSortOption,
        onMemoStartDateSelected = viewModel.updateMemoStartDate,
        onMemoEndDateSelected = viewModel.updateMemoEndDate,
        onDismissMemoFilterSheet = onDismissMemoFilterSheet,
        onHeatmapDateLongPress = onHeatmapDateLongPress,
        onScrollToTop = onScrollToTop,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainScreenRenderHost(
    isExpanded: Boolean,
    drawerState: androidx.compose.material3.DrawerState,
    sidebarUiState: SidebarViewModel.SidebarUiState,
    actions: MainScreenActions,
    snackbarHostState: SnackbarHostState,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    selectedTag: String?,
    searchQuery: String,
    memoListFilter: MemoListFilter,
    isFilterActive: Boolean,
    isMemoFilterSheetVisible: Boolean,
    uiState: MainViewModel.MainScreenState,
    hasItems: Boolean,
    uiMemos: List<MemoUiModel>,
    deletingMemoIds: kotlinx.coroutines.flow.StateFlow<Set<String>>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isRefreshing: Boolean,
    onTodoClick: (Memo, Int, Boolean) -> Unit,
    dateFormat: String,
    timeFormat: String,
    onMemoDoubleClick: (Memo) -> Unit,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    onShowMemoMenu: (MemoMenuState) -> Unit,
    onMemoSortOptionSelected: (MemoSortOption) -> Unit,
    onMemoStartDateSelected: (LocalDate?) -> Unit,
    onMemoEndDateSelected: (LocalDate?) -> Unit,
    onDismissMemoFilterSheet: () -> Unit,
    onHeatmapDateLongPress: (LocalDate) -> Unit,
    onScrollToTop: () -> Unit,
) {
    MainScreenDrawerLayout(
        isExpanded = isExpanded,
        drawerState = drawerState,
        sidebarContent = {
            MainScreenSidebarContent(
                sidebarUiState = sidebarUiState,
                actions = actions,
                onHeatmapDateLongPress = onHeatmapDateLongPress,
            )
        },
    ) {
        MainScreenScaffoldContent(
            isExpanded = isExpanded,
            actions = actions,
            snackbarHostState = snackbarHostState,
            scrollBehavior = scrollBehavior,
            selectedTag = selectedTag,
            searchQuery = searchQuery,
            uiState = uiState,
            hasItems = hasItems,
            uiMemos = uiMemos,
            deletingMemoIds = deletingMemoIds,
            listState = listState,
            isRefreshing = isRefreshing,
            onTodoClick = onTodoClick,
            dateFormat = dateFormat,
            timeFormat = timeFormat,
            onMemoDoubleClick = onMemoDoubleClick,
            doubleTapEditEnabled = doubleTapEditEnabled,
            freeTextCopyEnabled = freeTextCopyEnabled,
            onShowMemoMenu = onShowMemoMenu,
            isFilterActive = isFilterActive,
            onScrollToTop = onScrollToTop,
            isMemoFilterSheetVisible = isMemoFilterSheetVisible,
            memoListFilter = memoListFilter,
            onMemoSortOptionSelected = onMemoSortOptionSelected,
            onMemoStartDateSelected = onMemoStartDateSelected,
            onMemoEndDateSelected = onMemoEndDateSelected,
            onDismissMemoFilterSheet = onDismissMemoFilterSheet,
        )
    }
}

@Composable
private fun MainScreenDrawerLayout(
    isExpanded: Boolean,
    drawerState: androidx.compose.material3.DrawerState,
    sidebarContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    if (isExpanded) {
        PermanentNavigationDrawer(
            drawerContent = {
                PermanentDrawerSheet(modifier = Modifier.width(300.dp)) {
                    sidebarContent()
                }
            },
            content = content,
        )
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(modifier = Modifier.fillMaxWidth(MAIN_SCREEN_MODAL_DRAWER_WIDTH_FRACTION)) {
                    sidebarContent()
                }
            },
            content = content,
        )
    }
}

@Composable
private fun MainScreenSidebarContent(
    sidebarUiState: SidebarViewModel.SidebarUiState,
    actions: MainScreenActions,
    onHeatmapDateLongPress: (LocalDate) -> Unit,
) {
    SidebarDrawer(
        username = "Lomo",
        stats = sidebarUiState.stats,
        memoCountByDate = sidebarUiState.memoCountByDate,
        tags = sidebarUiState.tags,
        onMemoClick = actions.onSidebarMemoClick,
        onTagClick = actions.onSidebarTagClick,
        onSettingsClick = actions.onSettings,
        onTrashClick = actions.onTrash,
        onDailyReviewClick = actions.onDailyReviewClick,
        onGalleryClick = actions.onGalleryClick,
        onHeatmapDateLongPress = onHeatmapDateLongPress,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun MainScreenAnimatedBody(
    uiState: MainViewModel.MainScreenState,
    searchQuery: String,
    selectedTag: String?,
    hasItems: Boolean,
    uiMemos: List<MemoUiModel>,
    deletingMemoIds: kotlinx.coroutines.flow.StateFlow<Set<String>>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onTodoClick: (Memo, Int, Boolean) -> Unit,
    dateFormat: String,
    timeFormat: String,
    onMemoDoubleClick: (Memo) -> Unit,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    onTagClick: (String) -> Unit,
    onImageClick: (ImageViewerRequest) -> Unit,
    onShowMemoMenu: (MemoMenuState) -> Unit,
    onSettings: () -> Unit,
) {
    AnimatedContent(
        targetState = uiState,
        transitionSpec = { mainScreenStateTransform() },
        label = "MainScreenStateTransition",
    ) { state ->
        when (state) {
            is MainViewModel.MainScreenState.Loading -> {
                com.lomo.ui.component.common.MemoListSkeleton(modifier = Modifier.fillMaxSize())
            }

            is MainViewModel.MainScreenState.NoDirectory -> {
                MainEmptyState(
                    searchQuery = searchQuery,
                    selectedTag = selectedTag,
                    hasDirectory = false,
                    onSettings = onSettings,
                )
            }

            is MainViewModel.MainScreenState.Ready -> {
                MainReadyContent(
                    hasItems = hasItems,
                    searchQuery = searchQuery,
                    selectedTag = selectedTag,
                    uiMemos = uiMemos,
                    deletingMemoIds = deletingMemoIds,
                    listState = listState,
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    onTodoClick = onTodoClick,
                    dateFormat = dateFormat,
                    timeFormat = timeFormat,
                    onMemoDoubleClick = onMemoDoubleClick,
                    doubleTapEditEnabled = doubleTapEditEnabled,
                    freeTextCopyEnabled = freeTextCopyEnabled,
                    onTagClick = onTagClick,
                    onImageClick = onImageClick,
                    onShowMemoMenu = onShowMemoMenu,
                    onSettings = onSettings,
                )
            }
        }
    }
}

@Composable
private fun MainReadyContent(
    hasItems: Boolean,
    searchQuery: String,
    selectedTag: String?,
    uiMemos: List<MemoUiModel>,
    deletingMemoIds: kotlinx.coroutines.flow.StateFlow<Set<String>>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onTodoClick: (Memo, Int, Boolean) -> Unit,
    dateFormat: String,
    timeFormat: String,
    onMemoDoubleClick: (Memo) -> Unit,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    onTagClick: (String) -> Unit,
    onImageClick: (ImageViewerRequest) -> Unit,
    onShowMemoMenu: (MemoMenuState) -> Unit,
    onSettings: () -> Unit,
) {
    MainReadyStateEnterContainer {
        Crossfade(
            targetState = hasItems,
            animationSpec =
                tween(
                    durationMillis = MotionTokens.DurationMedium2,
                    easing = MotionTokens.EasingStandard,
                ),
            label = "ReadyContentCrossfade",
        ) { showList ->
            if (!showList) {
                MainEmptyState(
                    searchQuery = searchQuery,
                    selectedTag = selectedTag,
                    hasDirectory = true,
                    onSettings = onSettings,
                )
            } else {
                MemoListContent(
                    memos = uiMemos,
                    deletingMemoIds = deletingMemoIds,
                    listState = listState,
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    onTodoClick = onTodoClick,
                    dateFormat = dateFormat,
                    timeFormat = timeFormat,
                    onMemoDoubleClick = onMemoDoubleClick,
                    doubleTapEditEnabled = doubleTapEditEnabled,
                    freeTextCopyEnabled = freeTextCopyEnabled,
                    onTagClick = onTagClick,
                    onImageClick = onImageClick,
                    onShowMemoMenu = onShowMemoMenu,
                )
            }
        }
    }
}

private fun mainScreenStateTransform(): androidx.compose.animation.ContentTransform =
    (
        fadeIn(
            animationSpec =
                tween(
                    durationMillis = MotionTokens.DurationLong2,
                    easing = MotionTokens.EasingStandard,
                ),
        ) +
            scaleIn(
                initialScale = 0.92f,
                animationSpec =
                    tween(
                        durationMillis = MotionTokens.DurationLong2,
                        easing = MotionTokens.EasingEmphasizedDecelerate,
                    ),
            )
    ) togetherWith
        fadeOut(
            animationSpec =
                tween(
                    durationMillis = MotionTokens.DurationLong2,
                    easing = MotionTokens.EasingStandard,
                ),
        )
