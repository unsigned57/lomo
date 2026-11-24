package com.lomo.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.lomo.app.PendingWidgetNavigationRequest
import com.lomo.app.WidgetDestination
import com.lomo.app.feature.gallery.GalleryReelMode
import com.lomo.app.feature.gallery.GalleryReelRequest
import com.lomo.app.feature.gallery.GalleryReelScreen
import com.lomo.app.feature.gallery.GalleryScreen
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.main.MainScreen
import com.lomo.app.feature.main.MainViewModel
import com.lomo.app.feature.main.MemoListExpandCoordinator
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.feature.main.rememberMainMemoListExpandCoordinator
import com.lomo.app.feature.search.SearchScreen
import com.lomo.app.feature.settings.SettingsScreen
import com.lomo.app.feature.share.ShareScreen
import com.lomo.app.feature.synccenter.SyncCenterRoute
import com.lomo.app.feature.synccenter.SyncCenterViewModel
import com.lomo.app.feature.tag.TagFilterScreen
import com.lomo.app.feature.trash.TrashScreen
import com.lomo.app.util.activityKoinViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private const val MAIN_ROUTE_NAME = NavRouteSerialNames.MAIN

/**
 * Main navigation host for the Memos app.
 * Extracted from MainActivity to improve maintainability.
 */
@Composable
internal fun LomoNavHost(
    navController: NavHostController,
    foregroundEntryId: Long = 0L,
    suppressForegroundAutoInput: Boolean = false,
    pendingWidgetNavigation: PendingWidgetNavigationRequest? = null,
    onConsumeWidgetNavigation: (Long) -> Unit = {},
) {
    val lanShareAvailabilityViewModel: LanShareAvailabilityViewModel = activityKoinViewModel()
    val lanShareEnabled by lanShareAvailabilityViewModel.lanShareEnabled.collectAsStateWithLifecycle()
    var mainForegroundEntryId by remember { mutableLongStateOf(0L) }
    val popBackStackSafely = rememberBackNavigationAction(navController = navController)
    val navigateToShare =
        rememberShareNavigationAction(
            navController = navController,
            lanShareEnabled = lanShareEnabled,
        )
    val navigateToImage = rememberImageNavigationAction(navController = navController)

    ForegroundMainEntryObserver(
        navController = navController,
        foregroundEntryId = foregroundEntryId,
        suppressForegroundAutoInput = suppressForegroundAutoInput,
        onResolvedMainForegroundEntryId = { resolved -> mainForegroundEntryId = resolved },
    )

    LaunchedEffect(pendingWidgetNavigation) {
        val request = pendingWidgetNavigation ?: return@LaunchedEffect
        val route =
            when (request.destination) {
                WidgetDestination.SEARCH -> NavRoute.Search
                WidgetDestination.DAILY_REVIEW -> NavRoute.DailyReview
                WidgetDestination.STATISTICS -> NavRoute.Statistics
            }
        navController.navigate(route) {
            launchSingleTop = true
        }
        onConsumeWidgetNavigation(request.id)
    }

    LomoNavigationGraph(
        navController = navController,
        popBackStackSafely = popBackStackSafely,
        navigateToShare = navigateToShare,
        navigateToImage = navigateToImage,
        lanShareEnabled = lanShareEnabled,
        mainForegroundEntryId = mainForegroundEntryId,
    )
}

@Composable
private fun ForegroundMainEntryObserver(
    navController: NavHostController,
    foregroundEntryId: Long,
    suppressForegroundAutoInput: Boolean,
    onResolvedMainForegroundEntryId: (Long) -> Unit,
) {
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.run { destination.route }
    var evaluatedForegroundEntryId by remember { mutableLongStateOf(0L) }
    var mainForegroundEntryId by remember { mutableLongStateOf(0L) }

    LaunchedEffect(foregroundEntryId, currentRoute, suppressForegroundAutoInput) {
        val nextState =
            MainForegroundEntryRoutingPolicy.resolve(
                foregroundEntryId = foregroundEntryId,
                evaluatedForegroundEntryId = evaluatedForegroundEntryId,
                currentMainForegroundEntryId = mainForegroundEntryId,
                hasVisibleDestination = currentRoute != null,
                isMainVisible = currentRoute == MAIN_ROUTE_NAME,
                suppressForegroundAutoInput = suppressForegroundAutoInput,
            )
        evaluatedForegroundEntryId = nextState.evaluatedForegroundEntryId
        if (nextState.mainForegroundEntryId != mainForegroundEntryId) {
            mainForegroundEntryId = nextState.mainForegroundEntryId
            onResolvedMainForegroundEntryId(nextState.mainForegroundEntryId)
        }
    }
}

@Stable
private class LomoNavigationContext(
    val transitions: NavigationTransitions,
    val navController: NavHostController,
    val popBackStackSafely: () -> Unit,
    val navigateToShare: (String, Long) -> Unit,
    val navigateToImage: (ImageViewerRequest) -> Unit,
    val lanShareEnabled: Boolean,
)

@Stable
private class MainRouteHoistedState(
    val pagedUiMemos: LazyPagingItems<MemoUiModel>,
    val expandCoordinator: MemoListExpandCoordinator,
)

@Composable
private fun rememberShareNavigationAction(
    navController: NavHostController,
    lanShareEnabled: Boolean,
): (String, Long) -> Unit = remember(navController, lanShareEnabled) {
    { content, timestamp ->
        if (lanShareEnabled) {
            val payloadKey = ShareRoutePayloadStore.putMemoContent(content)
            navController.navigate(
                NavRoute.Share(
                    payloadKey = payloadKey,
                    memoTimestamp = timestamp,
                ),
            )
        }
    }
}

@Composable
private fun rememberImageNavigationAction(navController: NavHostController): (ImageViewerRequest) -> Unit =
    remember(navController) {
        { request ->
            val encodedFallbackUrl = URLEncoder.encode(
                request.imageUrls[request.initialIndex],
                StandardCharsets.UTF_8.toString(),
            )
            val payloadKey = ImageViewerRoutePayloadStore.put(request)
            navController.navigate(
                NavRoute.ImageViewer(
                    url = encodedFallbackUrl,
                    payloadKey = payloadKey,
                    initialIndex = request.initialIndex,
                    memoId = request.memoId,
                ),
            )
        }
    }

@Composable
private fun LomoNavigationGraph(
    navController: NavHostController,
    popBackStackSafely: () -> Unit,
    navigateToShare: (String, Long) -> Unit,
    navigateToImage: (ImageViewerRequest) -> Unit,
    lanShareEnabled: Boolean,
    mainForegroundEntryId: Long,
) {
    val motionScheme = com.lomo.ui.theme.LocalLomoMotion.current.layout
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val pageOffsetPx = with(density) { com.lomo.ui.theme.PageTransitionDistance.toPx() }
    val transitions =
        remember(motionScheme, layoutDirection, pageOffsetPx) {
            NavigationTransitions(motionScheme, layoutDirection, pageOffsetPx)
        }
    val mainViewModel: MainViewModel = activityKoinViewModel()
    val mainPagedUiMemos = mainViewModel.pagedUiMemos.collectAsLazyPagingItems()
    val mainExpandCoordinator = rememberMainMemoListExpandCoordinator()
    val navContext =
        remember(
            transitions,
            navController,
            popBackStackSafely,
            navigateToShare,
            navigateToImage,
            lanShareEnabled,
        ) {
            LomoNavigationContext(
                transitions = transitions,
                navController = navController,
                popBackStackSafely = popBackStackSafely,
                navigateToShare = navigateToShare,
                navigateToImage = navigateToImage,
                lanShareEnabled = lanShareEnabled,
            )
        }
    val mainRouteState =
        remember(mainPagedUiMemos, mainExpandCoordinator) {
            MainRouteHoistedState(
                pagedUiMemos = mainPagedUiMemos,
                expandCoordinator = mainExpandCoordinator,
            )
        }
    NavHost(
        navController = navController,
        startDestination = NavRoute.Main,
        enterTransition = transitions.standardEnter,
        exitTransition = transitions.standardExit,
        popEnterTransition = transitions.standardPopEnter,
        popExitTransition = transitions.standardPopExit,
        predictivePopEnterTransition = { transitions.standardPopEnter(this) },
        predictivePopExitTransition = { transitions.standardPopExit(this) },
    ) {
        addSharedTransitionDestinations(
            navContext = navContext,
            mainForegroundEntryId = mainForegroundEntryId,
            mainRouteState = mainRouteState,
        )
        addNonSharedDestinations(
            transitions = transitions,
            navigateToImage = navigateToImage,
            navController = navController,
            popBackStackSafely = popBackStackSafely,
            navigateToShare = navigateToShare,
            lanShareEnabled = lanShareEnabled,
        )
        addGalleryReelDestination(
            transitions = transitions,
            navController = navController,
            popBackStackSafely = popBackStackSafely,
            navigateToShare = navigateToShare,
            lanShareEnabled = lanShareEnabled,
        )
    }
}

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
private fun NavGraphBuilder.addSharedTransitionDestinations(
    navContext: LomoNavigationContext,
    mainForegroundEntryId: Long,
    mainRouteState: MainRouteHoistedState,
) {
    composable<NavRoute.Main> {
        // No SharedTransitionLayout: it creates a LookaheadScope around the whole feed, and any
        // size-animating modifier on a LazyColumn item (insert grow / delete collapse / card expand)
        // then crashes during fast fling ("LookaheadDelegate has not been measured yet"). The
        // image→viewer shared-element transition is given up in favor of the list size animations;
        // readers of LocalSharedTransitionScope degrade gracefully when it is null.
        MainRouteEntry(
            navController = navContext.navController,
            navigateToImage = navContext.navigateToImage,
            navigateToShare = navContext.navigateToShare,
            lanShareEnabled = navContext.lanShareEnabled,
            foregroundEntryId = mainForegroundEntryId,
            mainRouteState = mainRouteState,
        )
    }

    composable<NavRoute.Tag> { backStackEntry ->
        // No SharedTransitionLayout — TagFilterScreen shows the same MemoCardList feed as Main, so its
        // LookaheadScope would crash the feed during fast fling (see NavRoute.Main).
        val mainViewModel: MainViewModel = activityKoinViewModel()
        val tag = backStackEntry.toRoute<NavRoute.Tag>()
        TagFilterScreen(
            tagName = tag.tagName,
            onBackClick = navContext.popBackStackSafely,
            onNavigateToImage = navContext.navigateToImage,
            onNavigateToShare = navContext.navigateToShare,
            onRequestFocusMemo = mainViewModel.requestFocusMemoInDefaultMainList,
            onNavigateToMain = { navContext.navController.popBackStackOrNavigateMain() },
            onNavigateToTag = { otherTag -> navContext.navController.navigate(NavRoute.Tag(otherTag)) },
            lanShareEnabled = navContext.lanShareEnabled,
        )
    }

    composable<NavRoute.DailyReview> {
        val animatedVisibilityScope = this
        androidx.compose.animation.SharedTransitionLayout {
            val mainViewModel: MainViewModel = activityKoinViewModel()
            ProvideSharedAnimationLocals(
                sharedTransitionScope = this,
                animatedVisibilityScope = animatedVisibilityScope,
            ) {
                com.lomo.app.feature.review.DailyReviewScreen(
                    onBackClick = navContext.popBackStackSafely,
                    onNavigateToImage = navContext.navigateToImage,
                    onNavigateToShare = navContext.navigateToShare,
                    lanShareEnabled = navContext.lanShareEnabled,
                    onRequestFocusMemo = mainViewModel.requestFocusMemoInDefaultMainList,
                    onNavigateToMain = { navContext.navController.popBackStackOrNavigateMain() },
                )
            }
        }
    }

    composable<NavRoute.Gallery> {
        val animatedVisibilityScope = this
        androidx.compose.animation.SharedTransitionLayout {
            val mainViewModel: MainViewModel = activityKoinViewModel()
            val galleryItems = mainViewModel.galleryPagedUiItems.collectAsLazyPagingItems()
            val navigateToGalleryReel =
                rememberGalleryReelNavigationAction(
                    navController = navContext.navController,
                    galleryItems = galleryItems.itemSnapshotList.items,
                )
            ProvideSharedAnimationLocals(
                sharedTransitionScope = this,
                animatedVisibilityScope = animatedVisibilityScope,
            ) {
                GalleryScreen(
                    onBackClick = navContext.popBackStackSafely,
                    onNavigateToReel = navigateToGalleryReel,
                    onNavigateToShare = navContext.navigateToShare,
                    onNavigateToMain = { navContext.navController.popBackStackOrNavigateMain() },
                    lanShareEnabled = navContext.lanShareEnabled,
                )
            }
        }
    }
    addImageViewerDestination(
        transitions = navContext.transitions,
        popBackStackSafely = navContext.popBackStackSafely,
        navController = navContext.navController,
    )
}

@Composable
private fun MainRouteEntry(
    navController: NavHostController,
    navigateToImage: (ImageViewerRequest) -> Unit,
    navigateToShare: (String, Long) -> Unit,
    lanShareEnabled: Boolean,
    foregroundEntryId: Long,
    mainRouteState: MainRouteHoistedState,
) {
    MainScreen(
        onNavigateToSettings = remember(navController) { { navController.navigate(NavRoute.Settings) } },
        onNavigateToTrash = remember(navController) { { navController.navigate(NavRoute.Trash) } },
        onNavigateToSearch = remember(navController) { { navController.navigate(NavRoute.Search) } },
        onNavigateToTag = remember(navController) { { tag -> navController.navigate(NavRoute.Tag(tag)) } },
        onNavigateToImage = navigateToImage,
        onNavigateToDailyReview = remember(navController) { { navController.navigate(NavRoute.DailyReview) } },
        onNavigateToGallery = remember(navController) { { navController.navigate(NavRoute.Gallery) } },
        onNavigateToStatistics = remember(navController) { { navController.navigate(NavRoute.Statistics) } },
        onNavigateToShare = navigateToShare,
        lanShareEnabled = lanShareEnabled,
        foregroundEntryId = foregroundEntryId,
        pagedUiMemos = mainRouteState.pagedUiMemos,
        expandCoordinator = mainRouteState.expandCoordinator,
    )
}

private fun NavGraphBuilder.addNonSharedDestinations(
    transitions: NavigationTransitions,
    navigateToImage: (ImageViewerRequest) -> Unit,
    navController: NavHostController,
    popBackStackSafely: () -> Unit,
    navigateToShare: (String, Long) -> Unit,
    lanShareEnabled: Boolean,
) {
    composable<NavRoute.Settings> {
        SettingsScreen(
            onBackClick = popBackStackSafely,
            onNavigateToSyncCenter = { navController.navigate(NavRoute.SyncCenter) },
        )
    }

    composable<NavRoute.SyncCenter> {
        val syncCenterViewModel: SyncCenterViewModel = activityKoinViewModel()
        SyncCenterRoute(
            viewModel = syncCenterViewModel,
            onClose = popBackStackSafely,
        )
    }

    composable<NavRoute.Trash> {
        val mainViewModel: MainViewModel = activityKoinViewModel()
        TrashScreen(
            onBackClick = popBackStackSafely,
            onNavigateToImage = navigateToImage,
            onRequestFocusMemo = mainViewModel.requestFocusMemoInDefaultMainList,
        )
    }

    composable<NavRoute.Search>(
        enterTransition = transitions.searchEnter,
        exitTransition = transitions.searchExit,
        popEnterTransition = transitions.searchPopEnter,
        popExitTransition = transitions.searchPopExit,
    ) {
        val mainViewModel: MainViewModel = activityKoinViewModel()
        SearchScreen(
            onNavigateToImage = navigateToImage,
            onBackClick = popBackStackSafely,
            onNavigateToShare = navigateToShare,
            onRequestFocusMemo = mainViewModel.requestFocusMemoInDefaultMainList,
            onNavigateToMain = { navController.popBackStackOrNavigateMain() },
            lanShareEnabled = lanShareEnabled,
        )
    }

    composable<NavRoute.Statistics> {
        com.lomo.app.feature.statistics.StatisticsScreen(
            onBackClick = popBackStackSafely,
        )
    }

    composable<NavRoute.Share> {
        ShareScreen(onBackClick = popBackStackSafely)
    }
}

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
private fun NavGraphBuilder.addImageViewerDestination(
    transitions: NavigationTransitions,
    navController: NavHostController,
    popBackStackSafely: () -> Unit,
) {
    composable<NavRoute.ImageViewer>(
        enterTransition = transitions.imageViewerEnter,
        exitTransition = transitions.imageViewerExit,
        popEnterTransition = transitions.imageViewerPopEnter,
        popExitTransition = transitions.imageViewerPopExit,
    ) { entry ->
        val animatedVisibilityScope = this
        androidx.compose.animation.SharedTransitionLayout {
            val route = entry.toRoute<NavRoute.ImageViewer>()
            val mainViewModel: MainViewModel = activityKoinViewModel()
            val liveMemoFlow = androidx.compose.runtime.remember(mainViewModel, route.memoId) {
                route.memoId?.let(mainViewModel.observeMemoUiModel)
                    ?: kotlinx.coroutines.flow.flowOf(null)
            }
            val liveMemo by liveMemoFlow.collectAsStateWithLifecycle(initialValue = null)
            val decodedUrl = URLDecoder.decode(route.url, StandardCharsets.UTF_8.toString())
            val imagePayload = androidx.compose.runtime.remember(route.payloadKey) {
                ImageViewerRoutePayloadStore.get(route.payloadKey)
            }
            val imageUrls =
                androidx.compose.runtime.remember(imagePayload, decodedUrl) {
                    imagePayload?.imageUrls
                        ?: decodedUrl.takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
                }
            val singleMemoViewer =
                androidx.compose.runtime.remember(
                    imagePayload,
                    imageUrls,
                    route.initialIndex,
                    liveMemo,
                ) {
                    buildSingleMemoGalleryReelRequest(
                        sourceMemo = liveMemo ?: imagePayload?.memo,
                        imageUrls = imageUrls,
                        initialIndex = route.initialIndex,
                    )
                }
            ProvideSharedAnimationLocals(
                sharedTransitionScope = this,
                animatedVisibilityScope = animatedVisibilityScope,
            ) {
                GalleryReelScreen(
                    onTagClick = { tag -> navController.navigate(NavRoute.Tag(tag)) },
                    request = singleMemoViewer.request,
                    viewerMode = GalleryReelMode.SingleMemo,
                    memoChromeEnabled = singleMemoViewer.memoChromeEnabled,
                    activeMemoDetail = singleMemoViewer.memoDetail,
                    onBackClick = popBackStackSafely,
                    onTodoClick = mainViewModel.updateMemo,
                )
            }
        }
    }
}

private data class SingleMemoViewerRequest(
    val request: GalleryReelRequest,
    val memoDetail: MemoUiModel?,
    val memoChromeEnabled: Boolean,
)

private fun buildSingleMemoGalleryReelRequest(
    sourceMemo: MemoUiModel?,
    imageUrls: List<String>,
    initialIndex: Int,
): SingleMemoViewerRequest {
    val normalizedUrls =
        imageUrls
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
    val entry =
        when {
            sourceMemo != null -> {
                val resolvedUrls = sourceMemo.imageUrls.ifEmpty { normalizedUrls.toImmutableList() }
                if (resolvedUrls.isEmpty()) {
                    null
                } else {
                    com.lomo.app.feature.gallery.GalleryReelEntry(
                        memoId = sourceMemo.memo.id,
                        imageUrls = resolvedUrls,
                    )
                }
            }

            normalizedUrls.isNotEmpty() ->
                com.lomo.app.feature.gallery.GalleryReelEntry(
                    memoId = "image-viewer:${normalizedUrls.joinToString(separator = "|").hashCode()}",
                    imageUrls = normalizedUrls.toImmutableList(),
                )

            else -> null
        }
    val entries =
        if (entry == null) {
            persistentListOf()
        } else {
            listOf(entry).toImmutableList()
        }
    val pageCount = entry?.imageUrls?.size ?: 0
    val clampedIndex =
        if (pageCount == 0) {
            0
        } else {
            initialIndex.coerceIn(0, pageCount - 1)
        }

    return SingleMemoViewerRequest(
        request =
        GalleryReelRequest(
            entries = entries,
            initialMemoIndex = 0,
            initialImageIndex = clampedIndex,
        ),
        memoDetail = sourceMemo,
        memoChromeEnabled = sourceMemo != null,
    )
}
