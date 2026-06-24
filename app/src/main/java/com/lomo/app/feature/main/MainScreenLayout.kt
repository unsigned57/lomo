package com.lomo.app.feature.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import com.lomo.ui.component.common.EnterAnimationRegistry
import androidx.paging.compose.LazyPagingItems
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoSortOption
import com.lomo.app.feature.common.MemoListFilterController
import com.lomo.ui.component.common.ExpressiveLoadingIndicator
import com.lomo.app.feature.memo.MemoMenuSelection
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
    pagedUiMemos: LazyPagingItems<MemoUiModel>,
    hostState: MainScreenHostState,
    viewModel: MainViewModel,
    actions: MainScreenActions,
    isRefreshing: Boolean,
    isMemoFilterSheetVisible: Boolean,
    onDismissMemoFilterSheet: () -> Unit,
    onHeatmapDateLongPress: (LocalDate) -> Unit,
    onScrollToTop: () -> Unit,
    onShowMemoMenu: (MemoMenuSelection) -> Unit,
    onReminderClick: (String, String) -> Unit,
    onOpenEditor: (Memo) -> Unit,
    onSidebarTagReorder: (List<String>) -> Unit,
) {
    MainScreenRenderHost(
        isExpanded = hostState.isExpanded,
        drawerState = hostState.drawerState,
        sidebarUiState = screenState.sidebarUiState,
        actions = actions,
        snackbarHostState = hostState.snackbarHostState,
        scrollBehavior = hostState.scrollBehavior,
        searchQuery = screenState.searchQuery,
        mainListTotalCount = screenState.mainListTotalCount,
        isFilterActive = screenState.memoListFilter.isActive,
        isMemoFilterSheetVisible = isMemoFilterSheetVisible,
        uiState = screenState.uiState,
        pagedUiMemos = pagedUiMemos,
        exitAnimationRegistry = viewModel.exitAnimationRegistry,
        enterAnimationRegistry = viewModel.enterAnimationRegistry,
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
        memoListFilterController = viewModel.memoListFilterController,
        onDismissMemoFilterSheet = onDismissMemoFilterSheet,
        onHeatmapDateLongPress = onHeatmapDateLongPress,
        onScrollToTop = onScrollToTop,
        onSidebarTagReorder = onSidebarTagReorder,
        onReminderClick = onReminderClick,
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
    mainListTotalCount: Int,
    isFilterActive: Boolean,
    isMemoFilterSheetVisible: Boolean,
    uiState: MainViewModel.MainScreenState,
    pagedUiMemos: LazyPagingItems<MemoUiModel>,
    exitAnimationRegistry: com.lomo.ui.component.common.ExitAnimationRegistry<MemoUiModel>,
    enterAnimationRegistry: EnterAnimationRegistry,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isRefreshing: Boolean,
    onTodoClick: (Memo, Int, Boolean) -> Unit,
    dateFormat: String,
    timeFormat: String,
    onMemoDoubleClick: (Memo) -> Unit,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    scrollbarEnabled: Boolean,
    onShowMemoMenu: (MemoMenuSelection) -> Unit,
    memoListFilterController: MemoListFilterController,
    onDismissMemoFilterSheet: () -> Unit,
    onHeatmapDateLongPress: (LocalDate) -> Unit,
    onScrollToTop: () -> Unit,
    onSidebarTagReorder: (List<String>) -> Unit,
    onReminderClick: (String, String) -> Unit,
) {
    MainScreenDrawerLayout(
        isExpanded = isExpanded,
        drawerState = drawerState,
        sidebarContent = {
            MainScreenSidebarContent(
                sidebarUiState = sidebarUiState,
                actions = actions,
                onHeatmapDateLongPress = onHeatmapDateLongPress,
                onTagReorder = onSidebarTagReorder,
            )
        },
    ) {
        MainScreenScaffoldContent(
            isExpanded = isExpanded,
            actions = actions,
            snackbarHostState = snackbarHostState,
            scrollBehavior = scrollBehavior,
            searchQuery = searchQuery,
            mainListTotalCount = mainListTotalCount,
            uiState = uiState,
            pagedUiMemos = pagedUiMemos,
            exitAnimationRegistry = exitAnimationRegistry,
            enterAnimationRegistry = enterAnimationRegistry,
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
            memoListFilterController = memoListFilterController,
            onDismissMemoFilterSheet = onDismissMemoFilterSheet,
            onReminderClick = onReminderClick,
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
    val drawerSidebarContent = remember(sidebarContent) { movableContentOf { sidebarContent() } }
    if (isExpanded) {
        PermanentNavigationDrawer(
            drawerContent = {
                PermanentDrawerSheet(modifier = Modifier.width(300.dp)) {
                    drawerSidebarContent()
                }
            },
            content = content,
        )
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(modifier = Modifier.fillMaxWidth(MAIN_SCREEN_MODAL_DRAWER_WIDTH_FRACTION)) {
                    drawerSidebarContent()
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
    onTagReorder: (List<String>) -> Unit,
) {
    SidebarDrawer(
        username = "Lomo",
        stats = sidebarUiState.stats,
        memoCountByDate = sidebarUiState.memoCountByDate.toImmutableMap(),
        today = LocalDate.now(),
        tags = sidebarUiState.tags.toImmutableList(),
        rootTagOrder = sidebarUiState.rootTagOrder.toImmutableList(),
        onTagClick = actions.onSidebarTagClick,
        onTagReorder = onTagReorder,
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
    mainListTotalCount: Int,
    pagedUiMemos: LazyPagingItems<MemoUiModel>,
    exitAnimationRegistry: com.lomo.ui.component.common.ExitAnimationRegistry<MemoUiModel>,
    enterAnimationRegistry: EnterAnimationRegistry,
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
    onShowMemoMenu: (MemoMenuSelection) -> Unit,
    onReminderClick: (String, String) -> Unit,
    onSettings: () -> Unit,
    isFilterActive: Boolean,
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
                    pagedUiMemos = pagedUiMemos,
                    searchQuery = searchQuery,
                    mainListTotalCount = mainListTotalCount,
                    exitAnimationRegistry = exitAnimationRegistry,
                    enterAnimationRegistry = enterAnimationRegistry,
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
                    onReminderClick = onReminderClick,
                    onSettings = onSettings,
                    isFilterActive = isFilterActive,
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
    pagedUiMemos: LazyPagingItems<MemoUiModel>,
    searchQuery: String,
    mainListTotalCount: Int,
    exitAnimationRegistry: com.lomo.ui.component.common.ExitAnimationRegistry<MemoUiModel>,
    enterAnimationRegistry: EnterAnimationRegistry,
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
    onShowMemoMenu: (MemoMenuSelection) -> Unit,
    onReminderClick: (String, String) -> Unit,
    onSettings: () -> Unit,
    isFilterActive: Boolean,
) {
    val readyContentState =
        resolvePagedMainReadyContentState(
            itemCount = pagedUiMemos.itemCount,
            refreshState = pagedUiMemos.loadState.refresh,
        )
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
                MainReadyContentState.Empty ->
                    MainEmptyState(
                        searchQuery = searchQuery,
                        hasDirectory = true,
                        onSettings = onSettings,
                    )
                MainReadyContentState.List ->
                    Crossfade(
                        targetState = isFilterActive,
                        animationSpec =
                            tween(
                                durationMillis = MotionTokens.DurationMedium2,
                                easing = MotionTokens.EasingStandard,
                            ),
                        label = "FilterActiveCrossfade",
                    ) { filterActive ->
                        androidx.compose.runtime.key(filterActive) {
                            MemoListContent(
                                pagedMemos = pagedUiMemos,
                                knownTotalItemCount = mainListTotalCount,
                                exitAnimationRegistry = exitAnimationRegistry,
                                enterAnimationRegistry = enterAnimationRegistry,
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
                                onReminderClick = onReminderClick,
                            )
                        }
                    }
            }
        }
    }
}

internal enum class MainReadyContentState {
    Empty,
    List,
}

internal fun resolvePagedMainReadyContentState(
    itemCount: Int,
    refreshState: LoadState,
): MainReadyContentState =
    when {
        itemCount > 0 -> MainReadyContentState.List
        refreshState is LoadState.NotLoading && !refreshState.endOfPaginationReached -> MainReadyContentState.List
        refreshState is LoadState.Loading -> MainReadyContentState.List
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
