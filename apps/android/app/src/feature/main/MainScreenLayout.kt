package com.lomo.app.feature.main

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.lomo.app.R
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.feature.common.MemoListFilterController
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.memo.MemoMenuSelection
import com.lomo.domain.model.CalendarHeatmapThresholds
import com.lomo.domain.model.Memo
import com.lomo.ui.component.common.EnterAnimationRegistry
import com.lomo.ui.component.common.LomoListItemMotionSpecs
import com.lomo.ui.component.common.LomoTextButton
import com.lomo.ui.component.navigation.SidebarDrawer
import com.lomo.ui.icon.LomoIcons
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.LocalLomoMotion
import kotlinx.coroutines.delay
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainScreenNavigationRender(
    screenState: MainScreenUiSnapshot,
    pagedUiMemos: LazyPagingItems<MemoUiModel>,
    hostState: MainScreenHostState,
    listBindings: MainListRenderBindings,
    actions: MainScreenActions,
    isRefreshing: Boolean,
    isMemoFilterSheetVisible: Boolean,
    onDismissMemoFilterSheet: () -> Unit,
    onHeatmapDateLongPress: (LocalDate) -> Unit,
    onShowMemoMenu: (MemoMenuSelection) -> Unit,
    onReminderClick: (String, String) -> Unit,
    onOpenEditor: (Memo) -> Unit,
    onSidebarTagReorder: (List<String>) -> Unit,
    expandCoordinator: MemoListExpandCoordinator? = null,
    fab: @Composable () -> Unit,
) {
    MainScreenRenderHost(
        sidebar = hostState.sidebar,
        sidebarUiState = screenState.sidebarUiState,
        calendarHeatmapThresholds = screenState.calendarHeatmapThresholds,
        actions = actions,
        snackbarHostState = hostState.snackbarHostState,
        scrollBehavior = hostState.scrollBehavior,
        searchQuery = screenState.searchQuery,
        isFilterActive = screenState.memoListFilter.isActive,
        isMemoFilterSheetVisible = isMemoFilterSheetVisible,
        uiState = screenState.uiState,
        pagedUiMemos = pagedUiMemos,
        exitAnimationRegistry = listBindings.exitAnimationRegistry,
        enterAnimationRegistry = listBindings.enterAnimationRegistry,
        listState = hostState.listState,
        isRefreshing = isRefreshing,
        onTodoClick = listBindings.onUpdateMemo,
        onMemoDoubleClick = onOpenEditor,
        doubleTapEditEnabled = screenState.doubleTapEditEnabled,
        freeTextCopyEnabled = screenState.freeTextCopyEnabled,
        scrollbarEnabled = screenState.scrollbarEnabled,
        onShowMemoMenu = onShowMemoMenu,
        memoListFilterController = listBindings.memoListFilterController,
        onDismissMemoFilterSheet = onDismissMemoFilterSheet,
        onHeatmapDateLongPress = onHeatmapDateLongPress,
        fab = fab,
        onSidebarTagReorder = onSidebarTagReorder,
        onReminderClick = onReminderClick,
        onRetryEngine = listBindings.onRetryEngineOpen,
        onRebuildDerivedIndex = listBindings.onRebuildDerivedIndex,
        onExportDiagnostics = listBindings.onExportRecoveryDiagnostics,
        expandCoordinator = expandCoordinator,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainScreenRenderHost(
    sidebar: MainSidebarState,
    sidebarUiState: SidebarViewModel.SidebarUiState,
    calendarHeatmapThresholds: CalendarHeatmapThresholds,
    actions: MainScreenActions,
    snackbarHostState: SnackbarHostState,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    searchQuery: String,
    isFilterActive: Boolean,
    isMemoFilterSheetVisible: Boolean,
    uiState: MainViewModel.MainScreenState,
    pagedUiMemos: LazyPagingItems<MemoUiModel>,
    exitAnimationRegistry: com.lomo.ui.component.common.ExitAnimationRegistry<MemoUiModel>,
    enterAnimationRegistry: EnterAnimationRegistry,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isRefreshing: Boolean,
    onTodoClick: (Memo, com.lomo.domain.model.markdown.MarkdownSourceSpan) -> Unit,
    onMemoDoubleClick: (Memo) -> Unit,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    scrollbarEnabled: Boolean,
    onShowMemoMenu: (MemoMenuSelection) -> Unit,
    memoListFilterController: MemoListFilterController,
    onDismissMemoFilterSheet: () -> Unit,
    onHeatmapDateLongPress: (LocalDate) -> Unit,
    onSidebarTagReorder: (List<String>) -> Unit,
    onReminderClick: (String, String) -> Unit,
    onRetryEngine: () -> Unit,
    onRebuildDerivedIndex: () -> Unit,
    onExportDiagnostics: () -> Unit,
    expandCoordinator: MemoListExpandCoordinator? = null,
    fab: @Composable () -> Unit,
) {
    MainScreenDrawerLayout(
        sidebar = sidebar,
        sidebarContent = { containerColor ->
            MainScreenSidebarContent(
                containerColor = containerColor,
                sidebarUiState = sidebarUiState,
                calendarHeatmapThresholds = calendarHeatmapThresholds,
                actions = actions,
                onHeatmapDateLongPress = onHeatmapDateLongPress,
                onTagReorder = onSidebarTagReorder,
            )
        },
    ) {
        MainScreenScaffoldContent(
            actions = actions,
            sidebarToggle = sidebar.toggleKind,
            snackbarHostState = snackbarHostState,
            scrollBehavior = scrollBehavior,
            searchQuery = searchQuery,
            uiState = uiState,
            pagedUiMemos = pagedUiMemos,
            exitAnimationRegistry = exitAnimationRegistry,
            enterAnimationRegistry = enterAnimationRegistry,
            listState = listState,
            isRefreshing = isRefreshing,
            onTodoClick = onTodoClick,
            onMemoDoubleClick = onMemoDoubleClick,
            doubleTapEditEnabled = doubleTapEditEnabled,
            freeTextCopyEnabled = freeTextCopyEnabled,
            scrollbarEnabled = scrollbarEnabled,
            onShowMemoMenu = onShowMemoMenu,
            isFilterActive = isFilterActive,
            fab = fab,
            isMemoFilterSheetVisible = isMemoFilterSheetVisible,
            memoListFilterController = memoListFilterController,
            onDismissMemoFilterSheet = onDismissMemoFilterSheet,
            onReminderClick = onReminderClick,
            onRetryEngine = onRetryEngine,
            onRebuildDerivedIndex = onRebuildDerivedIndex,
            onExportDiagnostics = onExportDiagnostics,
            expandCoordinator = expandCoordinator,
        )
    }
}

@Composable
private fun MainScreenDrawerLayout(
    sidebar: MainSidebarState,
    sidebarContent: @Composable (containerColor: Color) -> Unit,
    content: @Composable () -> Unit,
) {
    // Placement changes on rotation or fold; the sidebar keeps its tag expansion and scroll.
    val latestSidebarContent by rememberUpdatedState(sidebarContent)
    val latestContent by rememberUpdatedState(content)
    val drawerSidebarContent =
        remember { movableContentOf { containerColor: Color -> latestSidebarContent(containerColor) } }
    val feedContent = remember { movableContentOf { latestContent() } }
    when (sidebar.placement) {
        MainSidebarPlacement.DockedPane -> {
            val canvasColor = MaterialTheme.colorScheme.surface
            MainDockedSidebarLayout(
                paneShown = sidebar.isDockedPaneShown,
                canvasColor = canvasColor,
                pane = {
                    PermanentDrawerSheet(
                        modifier = Modifier.width(MainSidebarPaneWidth),
                        drawerContainerColor = Color.Transparent,
                    ) {
                        drawerSidebarContent(canvasColor)
                    }
                },
                feed = feedContent,
            )
        }

        MainSidebarPlacement.ModalDrawer ->
            MainModalSidebarLayout(
                drawerState = sidebar.drawerState,
                sidebarContent = drawerSidebarContent,
                content = feedContent,
            )
    }
}

/**
 * Large windows read as one surface: the sidebar and the feed share the window's surface color, so no
 * seam or second tone appears between them. The pane slides in from the start edge while the feed gives
 * up exactly the width it covers, so the feed, its top bar actions and the cards' menus always stay
 * inside the window.
 */
@Composable
private fun MainDockedSidebarLayout(
    paneShown: Boolean,
    canvasColor: Color,
    pane: @Composable () -> Unit,
    feed: @Composable () -> Unit,
) {
    val paneVisibility =
        animateFloatAsState(
            targetValue = if (paneShown) 1f else 0f,
            animationSpec = LocalLomoMotion.current.layout.defaultSpatialSpec(),
            label = "MainSidebarPaneVisibility",
        )
    val measurePolicy =
        remember(paneVisibility) {
            MultiContentMeasurePolicy { (paneSlot, feedSlot), constraints ->
                val paneWidthPx = MainSidebarPaneWidth.roundToPx()
                val geometry =
                    resolveMainDockedGeometry(
                        windowWidthPx = constraints.maxWidth,
                        paneWidthPx = paneWidthPx,
                        paneVisibility = paneVisibility.value,
                    )
                val panes = paneSlot.map { it.measure(Constraints.fixed(paneWidthPx, constraints.maxHeight)) }
                val feeds = feedSlot.map { it.measure(Constraints.fixed(geometry.feedWidthPx, constraints.maxHeight)) }
                layout(constraints.maxWidth, constraints.maxHeight) {
                    // A fully slid-out pane is not placed: nothing of it can be drawn, hit or read aloud.
                    if (geometry.feedOffsetPx > 0) panes.forEach { it.placeRelative(geometry.paneOffsetPx, 0) }
                    feeds.forEach { it.placeRelative(geometry.feedOffsetPx, 0) }
                }
            }
        }
    Box(modifier = Modifier.fillMaxSize().background(canvasColor)) {
        Layout(
            contents = listOf(pane, feed),
            modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                .clipToBounds(),
            measurePolicy = measurePolicy,
        )
    }
}

@Composable
private fun MainModalSidebarLayout(
    drawerState: androidx.compose.material3.DrawerState,
    sidebarContent: @Composable (containerColor: Color) -> Unit,
    content: @Composable () -> Unit,
) {
    var sheetWidth by remember { mutableIntStateOf(0) }
    var sidebarActivated by remember { mutableStateOf(false) }
    val sidebarVisible by remember(drawerState) {
        derivedStateOf {
            drawerState.currentValue == androidx.compose.material3.DrawerValue.Open ||
                drawerState.targetValue == androidx.compose.material3.DrawerValue.Open ||
                (sheetWidth > 0 && drawerState.currentOffset > -sheetWidth.toFloat())
        }
    }
    LaunchedEffect(sidebarVisible) {
        if (sidebarVisible) sidebarActivated = true
    }
    val density = LocalDensity.current
    val drawerWidth = with(density) { resolveMainModalDrawerWidth(LocalWindowInfo.current.containerSize.width.toDp()) }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            // The drawerState overload gives the sheet its own predictive-back handling,
            // and LargeEnd keeps the trailing edge expressive while the start stays flush.
            ModalDrawerSheet(
                drawerState = drawerState,
                modifier = Modifier.width(drawerWidth).onSizeChanged { sheetWidth = it.width },
                drawerShape = com.lomo.ui.theme.AppShapes.LargeEnd,
            ) {
                // Materialize on first use, then retain tag expansion and scroll state.
                if (sidebarVisible || sidebarActivated) sidebarContent(DrawerDefaults.modalContainerColor)
            }
        },
        content = content,
    )
}

@Composable
private fun MainScreenSidebarContent(
    containerColor: Color,
    sidebarUiState: SidebarViewModel.SidebarUiState,
    calendarHeatmapThresholds: CalendarHeatmapThresholds,
    actions: MainScreenActions,
    onHeatmapDateLongPress: (LocalDate) -> Unit,
    onTagReorder: (List<String>) -> Unit,
) {
    val today = remember(sidebarUiState.stats.dayCount) { LocalDate.now() }
    SidebarDrawer(
        stats = sidebarUiState.stats,
        memoCountByDate = sidebarUiState.memoCountByDate,
        today = today,
        tags = sidebarUiState.tags,
        calendarHeatmapThresholds = calendarHeatmapThresholds,
        containerColor = containerColor,
        rootTagOrder = sidebarUiState.rootTagOrder,
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
        dailyReviewAnchorTag = BenchmarkAnchorContract.DRAWER_DAILY_REVIEW,
        galleryAnchorTag = BenchmarkAnchorContract.DRAWER_GALLERY,
        statisticsAnchorTag = BenchmarkAnchorContract.DRAWER_STATISTICS,
        tagAnchorForPath = BenchmarkAnchorContract::drawerTag,
    )
}

@Composable
internal fun MainScreenBody(
    uiState: MainViewModel.MainScreenState,
    searchQuery: String,
    pagedUiMemos: LazyPagingItems<MemoUiModel>,
    exitAnimationRegistry: com.lomo.ui.component.common.ExitAnimationRegistry<MemoUiModel>,
    enterAnimationRegistry: EnterAnimationRegistry,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onTodoClick: (Memo, com.lomo.domain.model.markdown.MarkdownSourceSpan) -> Unit,
    onMemoDoubleClick: (Memo) -> Unit,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    scrollbarEnabled: Boolean,
    onTagClick: (String) -> Unit,
    onImageClick: (ImageViewerRequest) -> Unit,
    onShowMemoMenu: (MemoMenuSelection) -> Unit,
    onReminderClick: (String, String) -> Unit,
    onSettings: () -> Unit,
    onRetryEngine: () -> Unit,
    onRebuildDerivedIndex: () -> Unit,
    onExportDiagnostics: () -> Unit,
    expandCoordinator: MemoListExpandCoordinator? = null,
) {
    // Readiness publishes available content immediately; navigation and rows own their motion.
    when {
        uiState is MainViewModel.MainScreenState.Loading -> {
            com.lomo.ui.component.common.MemoListSkeleton(modifier = Modifier.fillMaxSize())
        }

        uiState is MainViewModel.MainScreenState.NoDirectory -> {
            MainEmptyState(
                searchQuery = searchQuery,
                hasDirectory = false,
                onSettings = onSettings,
            )
        }

        uiState is MainViewModel.MainScreenState.OpeningEngine -> {
            MainInitialImportingState(modifier = Modifier.fillMaxSize())
        }

        uiState is MainViewModel.MainScreenState.ReadOnlyRecovery && !uiState.retainedProjection -> {
            EngineReadOnlyRecoveryScreen(
                code = uiState.code,
                onRetry = onRetryEngine,
                retryEnabled = uiState.hasRecoveryTarget,
                onReselectWorkspace = onSettings,
                canRebuildDerivedIndex = uiState.canRebuildDerivedIndex,
                onRebuildDerivedIndex = onRebuildDerivedIndex,
                onExportDiagnostics = onExportDiagnostics,
            )
        }

        else -> Column {
            if (uiState is MainViewModel.MainScreenState.ReadOnlyRecovery) {
                RetainedProjectionRecoveryBanner(
                    retryEnabled = uiState.hasRecoveryTarget,
                    onRetryEngine = onRetryEngine,
                )
            }
            MainReadyContent(
                pagedUiMemos = pagedUiMemos,
                searchQuery = searchQuery,
                exitAnimationRegistry = exitAnimationRegistry,
                enterAnimationRegistry = enterAnimationRegistry,
                listState = listState,
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                onTodoClick = onTodoClick,
                onMemoDoubleClick = onMemoDoubleClick,
                doubleTapEditEnabled = doubleTapEditEnabled,
                freeTextCopyEnabled = freeTextCopyEnabled,
                scrollbarEnabled = scrollbarEnabled,
                onTagClick = onTagClick,
                onImageClick = onImageClick,
                onShowMemoMenu = onShowMemoMenu,
                onReminderClick = onReminderClick,
                onSettings = onSettings,
                expandCoordinator = expandCoordinator,
            )
        }
    }
}

@Composable
private fun MainInitialImportingState(modifier: Modifier = Modifier) {
    com.lomo.ui.component.common.MemoListSkeleton(modifier = modifier)
}

@Composable
private fun MainReadyContent(
    pagedUiMemos: LazyPagingItems<MemoUiModel>,
    searchQuery: String,
    exitAnimationRegistry: com.lomo.ui.component.common.ExitAnimationRegistry<MemoUiModel>,
    enterAnimationRegistry: EnterAnimationRegistry,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onTodoClick: (Memo, com.lomo.domain.model.markdown.MarkdownSourceSpan) -> Unit,
    onMemoDoubleClick: (Memo) -> Unit,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    scrollbarEnabled: Boolean,
    onTagClick: (String) -> Unit,
    onImageClick: (ImageViewerRequest) -> Unit,
    onShowMemoMenu: (MemoMenuSelection) -> Unit,
    onReminderClick: (String, String) -> Unit,
    onSettings: () -> Unit,
    expandCoordinator: MemoListExpandCoordinator? = null,
) {
    val motionScheme = com.lomo.ui.theme.LocalLomoMotion.current.layout
    val activeExits by exitAnimationRegistry.entries.collectAsStateWithLifecycle()
    val hasActiveExits = activeExits.isNotEmpty()

    val resolvedState =
        resolvePagedMainReadyContentState(
            itemCount = pagedUiMemos.itemCount,
            refreshState = pagedUiMemos.loadState.refresh,
            hasActiveExits = hasActiveExits,
        )
    var readyContentState by remember { mutableStateOf(resolvedState) }
    LaunchedEffect(resolvedState, hasActiveExits) {
        if (resolvedState == MainReadyContentState.Empty) {
            val delayMillis = if (hasActiveExits) {
                LomoListItemMotionSpecs.EXIT_ANIMATION_DURATION_MILLIS
            } else {
                150L
            }
            delay(delayMillis)
        }
        readyContentState = resolvedState
    }
    Crossfade(
        targetState = readyContentState,
        animationSpec =
        motionScheme.defaultEffectsSpec(),
        label = "ReadyContentCrossfade",
    ) { state ->
        when (state) {
            MainReadyContentState.Empty ->
                MainEmptyState(
                    searchQuery = searchQuery,
                    hasDirectory = true,
                    onSettings = onSettings,
                )

            MainReadyContentState.Error ->
                com.lomo.ui.component.common.EmptyState(
                    icon = LomoIcons.Error,
                    title =
                    androidx.compose.ui.res.stringResource(
                        com.lomo.app.R.string.memo_list_load_failed_title,
                    ),
                    description =
                    androidx.compose.ui.res.stringResource(
                        com.lomo.app.R.string.memo_list_load_failed_description,
                    ),
                    action = {
                        LomoTextButton(onClick = pagedUiMemos::retry) {
                            androidx.compose.material3.Text(
                                androidx.compose.ui.res.stringResource(com.lomo.app.R.string.action_retry),
                            )
                        }
                    },
                )

            MainReadyContentState.List ->
                MemoListContent(
                    pagedMemos = pagedUiMemos,
                    exitAnimationRegistry = exitAnimationRegistry,
                    enterAnimationRegistry = enterAnimationRegistry,
                    listState = listState,
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    onTodoClick = onTodoClick,
                    onMemoDoubleClick = onMemoDoubleClick,
                    doubleTapEditEnabled = doubleTapEditEnabled,
                    freeTextCopyEnabled = freeTextCopyEnabled,
                    scrollbarEnabled = scrollbarEnabled,
                    onTagClick = onTagClick,
                    onImageClick = onImageClick,
                    onShowMemoMenu = onShowMemoMenu,
                    onReminderClick = onReminderClick,
                    expandCoordinator = expandCoordinator,
                )
        }
    }
}

@Composable
private fun RetainedProjectionRecoveryBanner(retryEnabled: Boolean, onRetryEngine: () -> Unit) {
    Surface(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.Small),
        shape = AppShapes.Medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.MediumSmall, vertical = AppSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
        ) {
            Text(
                text = stringResource(R.string.engine_recovery_retained_notes),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            LomoTextButton(
                onClick = onRetryEngine,
                enabled = retryEnabled,
            ) {
                Text(stringResource(R.string.engine_recovery_retry))
            }
        }
    }
}

internal enum class MainReadyContentState {
    Empty,
    List,
    Error,
}

internal fun resolvePagedMainReadyContentState(
    itemCount: Int,
    refreshState: LoadState,
    hasActiveExits: Boolean,
): MainReadyContentState = when {
    hasActiveExits -> MainReadyContentState.List
    itemCount > 0 -> MainReadyContentState.List
    refreshState is LoadState.NotLoading && !refreshState.endOfPaginationReached -> MainReadyContentState.List
    refreshState is LoadState.Loading -> MainReadyContentState.List
    refreshState is LoadState.Error -> MainReadyContentState.Error
    else -> MainReadyContentState.Empty
}
