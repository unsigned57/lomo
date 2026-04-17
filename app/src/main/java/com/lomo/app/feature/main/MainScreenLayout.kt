package com.lomo.app.feature.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoSortOption
import com.lomo.ui.component.common.ExpressiveLoadingIndicator
import com.lomo.ui.component.menu.MemoMenuState
import com.lomo.ui.component.navigation.SidebarDrawer
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import com.lomo.ui.theme.MotionTokens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableSet
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
    val deletingMemoIds by viewModel.deletingMemoIds.collectAsStateWithLifecycle()
    val collapsingMemoIds by viewModel.collapsingMemoIds.collectAsStateWithLifecycle()
    MainScreenRenderHost(
        isExpanded = hostState.isExpanded,
        drawerState = hostState.drawerState,
        sidebarUiState = screenState.sidebarUiState,
        actions = actions,
        snackbarHostState = hostState.snackbarHostState,
        scrollBehavior = hostState.scrollBehavior,
        searchQuery = screenState.searchQuery,
        memoListFilter = screenState.memoListFilter,
        isFilterActive = screenState.memoListFilter.hasDateRange,
        isMemoFilterSheetVisible = isMemoFilterSheetVisible,
        uiState = screenState.uiState,
        hasRawItems = screenState.hasRawItems,
        uiMemos = screenState.uiMemos,
        visibleUiMemos = screenState.visibleUiMemos,
        deletingMemoIds = remember(deletingMemoIds) { deletingMemoIds.toImmutableSet() },
        collapsingMemoIds = remember(collapsingMemoIds) { collapsingMemoIds.toImmutableSet() },
        newMemoInsertAnimationState = hostState.newMemoInsertAnimationSession.state,
        onNewMemoSpacePrepared = hostState.newMemoInsertAnimationSession::markBlankSpacePrepared,
        onNewMemoRevealConsumed = hostState.newMemoInsertAnimationSession::clearReveal,
        listState = hostState.listState,
        isRefreshing = isRefreshing,
        onTodoClick = viewModel.updateMemo,
        dateFormat = screenState.dateFormat,
        timeFormat = screenState.timeFormat,
        onMemoDoubleClick = onOpenEditor,
        doubleTapEditEnabled = screenState.doubleTapEditEnabled,
        freeTextCopyEnabled = screenState.freeTextCopyEnabled,
        scrollbarEnabled = screenState.scrollbarEnabled,
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
    searchQuery: String,
    memoListFilter: MemoListFilter,
    isFilterActive: Boolean,
    isMemoFilterSheetVisible: Boolean,
    uiState: MainViewModel.MainScreenState,
    hasRawItems: Boolean,
    uiMemos: ImmutableList<MemoUiModel>,
    visibleUiMemos: ImmutableList<MemoUiModel>,
    deletingMemoIds: ImmutableSet<String>,
    collapsingMemoIds: ImmutableSet<String>,
    newMemoInsertAnimationState: NewMemoInsertAnimationState,
    onNewMemoSpacePrepared: (String) -> Unit,
    onNewMemoRevealConsumed: (String) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isRefreshing: Boolean,
    onTodoClick: (Memo, Int, Boolean) -> Unit,
    dateFormat: String,
    timeFormat: String,
    onMemoDoubleClick: (Memo) -> Unit,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    scrollbarEnabled: Boolean,
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
            searchQuery = searchQuery,
            uiState = uiState,
            hasRawItems = hasRawItems,
            uiMemos = uiMemos,
            visibleUiMemos = visibleUiMemos,
            deletingMemoIds = deletingMemoIds,
            collapsingMemoIds = collapsingMemoIds,
            newMemoInsertAnimationState = newMemoInsertAnimationState,
            onNewMemoSpacePrepared = onNewMemoSpacePrepared,
            onNewMemoRevealConsumed = onNewMemoRevealConsumed,
            listState = listState,
            isRefreshing = isRefreshing,
            onTodoClick = onTodoClick,
            dateFormat = dateFormat,
            timeFormat = timeFormat,
            onMemoDoubleClick = onMemoDoubleClick,
            doubleTapEditEnabled = doubleTapEditEnabled,
            freeTextCopyEnabled = freeTextCopyEnabled,
            scrollbarEnabled = scrollbarEnabled,
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
        memoCountByDate = sidebarUiState.memoCountByDate.toImmutableMap(),
        tags = sidebarUiState.tags.toImmutableList(),
        onTagClick = actions.onSidebarTagClick,
        onSettingsClick = actions.onSettings,
        onTrashClick = actions.onTrash,
        onDailyReviewClick = actions.onDailyReviewClick,
        onGalleryClick = actions.onGalleryClick,
        onStatisticsClick = actions.onStatisticsClick,
        onHeatmapDateLongPress = onHeatmapDateLongPress,
        modifier = Modifier.fillMaxWidth(),
        settingsAnchorTag = BenchmarkAnchorContract.DRAWER_SETTINGS,
        trashAnchorTag = BenchmarkAnchorContract.DRAWER_TRASH,
        tagAnchorForPath = BenchmarkAnchorContract::drawerTag,
    )
}

@Composable
internal fun MainScreenAnimatedBody(
    uiState: MainViewModel.MainScreenState,
    searchQuery: String,
    hasRawItems: Boolean,
    uiMemos: ImmutableList<MemoUiModel>,
    visibleUiMemos: ImmutableList<MemoUiModel>,
    deletingMemoIds: ImmutableSet<String>,
    collapsingMemoIds: ImmutableSet<String>,
    newMemoInsertAnimationState: NewMemoInsertAnimationState,
    onNewMemoSpacePrepared: (String) -> Unit,
    onNewMemoRevealConsumed: (String) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onTodoClick: (Memo, Int, Boolean) -> Unit,
    dateFormat: String,
    timeFormat: String,
    onMemoDoubleClick: (Memo) -> Unit,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    scrollbarEnabled: Boolean,
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
                    hasDirectory = false,
                    onSettings = onSettings,
                )
            }

            is MainViewModel.MainScreenState.InitialImporting -> {
                MainInitialImportingState(modifier = Modifier.fillMaxSize())
            }

            is MainViewModel.MainScreenState.Ready -> {
                MainReadyContent(
                    hasRawItems = hasRawItems,
                    searchQuery = searchQuery,
                    uiMemos = uiMemos,
                    visibleUiMemos = visibleUiMemos,
                    deletingMemoIds = deletingMemoIds,
                    collapsingMemoIds = collapsingMemoIds,
                    newMemoInsertAnimationState = newMemoInsertAnimationState,
                    onNewMemoSpacePrepared = onNewMemoSpacePrepared,
                    onNewMemoRevealConsumed = onNewMemoRevealConsumed,
                    listState = listState,
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    onTodoClick = onTodoClick,
                    dateFormat = dateFormat,
                    timeFormat = timeFormat,
                    onMemoDoubleClick = onMemoDoubleClick,
                    doubleTapEditEnabled = doubleTapEditEnabled,
                    freeTextCopyEnabled = freeTextCopyEnabled,
                    scrollbarEnabled = scrollbarEnabled,
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
private fun MainInitialImportingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        ExpressiveLoadingIndicator(modifier = Modifier.size(56.dp))
    }
}

@Composable
private fun MainReadyContent(
    hasRawItems: Boolean,
    searchQuery: String,
    uiMemos: ImmutableList<MemoUiModel>,
    visibleUiMemos: ImmutableList<MemoUiModel>,
    deletingMemoIds: ImmutableSet<String>,
    collapsingMemoIds: ImmutableSet<String>,
    newMemoInsertAnimationState: NewMemoInsertAnimationState,
    onNewMemoSpacePrepared: (String) -> Unit,
    onNewMemoRevealConsumed: (String) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onTodoClick: (Memo, Int, Boolean) -> Unit,
    dateFormat: String,
    timeFormat: String,
    onMemoDoubleClick: (Memo) -> Unit,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    scrollbarEnabled: Boolean,
    onTagClick: (String) -> Unit,
    onImageClick: (ImageViewerRequest) -> Unit,
    onShowMemoMenu: (MemoMenuState) -> Unit,
    onSettings: () -> Unit,
) {
    val readyContentState = resolveMainReadyContentState(hasRawItems = hasRawItems, uiMemos = uiMemos)
    MainReadyStateEnterContainer {
        Crossfade(
            targetState = readyContentState,
            animationSpec =
                tween(
                    durationMillis = MotionTokens.DurationMedium2,
                    easing = MotionTokens.EasingStandard,
                ),
            label = "ReadyContentCrossfade",
        ) { state ->
            when (state) {
                MainReadyContentState.Loading -> MainInitialImportingState(modifier = Modifier.fillMaxSize())
                MainReadyContentState.Empty ->
                    MainEmptyState(
                        searchQuery = searchQuery,
                        hasDirectory = true,
                        onSettings = onSettings,
                    )
                MainReadyContentState.List ->
                    MemoListContent(
                        memos = visibleUiMemos,
                        deletingMemoIds = deletingMemoIds,
                        collapsingMemoIds = collapsingMemoIds,
                        newMemoInsertAnimationState = newMemoInsertAnimationState,
                        onNewMemoSpacePrepared = onNewMemoSpacePrepared,
                        onNewMemoRevealConsumed = onNewMemoRevealConsumed,
                        listState = listState,
                        isRefreshing = isRefreshing,
                        onRefresh = onRefresh,
                        onTodoClick = onTodoClick,
                        dateFormat = dateFormat,
                        timeFormat = timeFormat,
                        onMemoDoubleClick = onMemoDoubleClick,
                        doubleTapEditEnabled = doubleTapEditEnabled,
                        freeTextCopyEnabled = freeTextCopyEnabled,
                        scrollbarEnabled = scrollbarEnabled,
                        onTagClick = onTagClick,
                        onImageClick = onImageClick,
                        onShowMemoMenu = onShowMemoMenu,
                    )
            }
        }
    }
}

internal enum class MainReadyContentState {
    Loading,
    Empty,
    List,
}

internal fun resolveMainReadyContentState(
    hasRawItems: Boolean,
    uiMemos: ImmutableList<MemoUiModel>,
): MainReadyContentState =
    when {
        uiMemos.isNotEmpty() -> MainReadyContentState.List
        hasRawItems -> MainReadyContentState.Loading
        else -> MainReadyContentState.Empty
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
