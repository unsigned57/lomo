package com.lomo.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import androidx.paging.compose.collectAsLazyPagingItems
import com.lomo.app.feature.gallery.GalleryReelEntry
import com.lomo.app.feature.gallery.GalleryReelMode
import com.lomo.app.feature.gallery.GalleryReelRequest
import com.lomo.app.feature.gallery.GalleryReelScreen
import com.lomo.app.feature.gallery.GalleryUiItem
import com.lomo.app.feature.main.MainViewModel
import com.lomo.app.feature.memo.MemoMenuBinder
import com.lomo.app.feature.memo.MemoMenuContextAction
import com.lomo.app.feature.memo.MemoMenuPresentationState
import com.lomo.app.feature.memo.handleMemoJumpToMain
import com.lomo.app.feature.memo.rememberMemoMenuCommandHandler
import com.lomo.app.util.activityKoinViewModel
import kotlinx.collections.immutable.toImmutableList

@Composable
internal fun rememberGalleryReelNavigationAction(
    navController: NavHostController,
    galleryItems: List<GalleryUiItem>,
): (memoId: String, imageIndex: Int, aspectByMemoId: Map<String, Float>) -> Unit =
    remember(navController, galleryItems) {
        { memoId, imageIndex, aspectByMemoId ->
            val items = galleryItems.filter { item -> item.imageUrls.isNotEmpty() }
            val initialMemoIndex = items.indexOfFirst { item -> item.memoId == memoId }
            val initialItem = items.getOrNull(initialMemoIndex)
            if (initialItem != null) {
                val clampedImageIndex = imageIndex.coerceIn(0, initialItem.imageUrls.lastIndex)
                val memoIds = items.map(GalleryUiItem::memoId)
                val payloadKey =
                    GalleryReelPayloadStore.put(
                        GalleryReelPayloadStore.Payload(
                            memoIds = memoIds,
                            aspectByMemoId = aspectByMemoId.filterKeys { key -> key in memoIds },
                        ),
                    )
                navController.navigate(
                    NavRoute.GalleryReel(
                        payloadKey = payloadKey,
                        initialMemoIndex = initialMemoIndex,
                        initialImageIndex = clampedImageIndex,
                    ),
                )
            }
        }
    }

internal fun NavGraphBuilder.addGalleryReelDestination(
    transitions: NavigationTransitions,
    navController: NavHostController,
    popBackStackSafely: () -> Unit,
    navigateToShare: (String, Long) -> Unit,
    lanShareEnabled: Boolean,
) {
    composable<NavRoute.GalleryReel>(
        enterTransition = transitions.imageViewerEnter,
        exitTransition = transitions.imageViewerExit,
        popEnterTransition = transitions.imageViewerPopEnter,
        popExitTransition = transitions.imageViewerPopExit,
    ) { entry ->
        val route = entry.toRoute<NavRoute.GalleryReel>()
        val mainViewModel: MainViewModel = activityKoinViewModel()
        val galleryItems = mainViewModel.galleryPagedUiItems.collectAsLazyPagingItems()
        val appPreferences by mainViewModel.appPreferences.collectAsStateWithLifecycle()
        val payload =
            remember(route.payloadKey) {
                GalleryReelPayloadStore.get(route.payloadKey)
            }

        if (payload == null) {
            androidx.compose.runtime.LaunchedEffect(route.payloadKey) {
                popBackStackSafely()
            }
            return@composable
        }

        val request =
            remember(payload, galleryItems.itemSnapshotList.items, route.initialMemoIndex, route.initialImageIndex) {
                buildGalleryReelRequest(
                    payload = payload,
                    galleryItems = galleryItems.itemSnapshotList.items,
                    initialMemoIndex = route.initialMemoIndex,
                    initialImageIndex = route.initialImageIndex,
                )
            }

        if (request == null) {
            // Gallery items are still loading after a cold start — wait for them
            // instead of treating the empty initial state as "memo removed" and
            // popping the route right away.
            return@composable
        }

        var activeMemoId by rememberSaveable(route.payloadKey) {
            mutableStateOf(request.entries.getOrNull(request.initialMemoIndex)?.memoId.orEmpty())
        }
        val activeMemoFlow =
            remember(mainViewModel, activeMemoId) {
                activeMemoId
                    .takeIf(String::isNotBlank)
                    ?.let(mainViewModel.observeMemoUiModel)
                    ?: kotlinx.coroutines.flow.flowOf(null)
            }
        val activeMemoDetail by activeMemoFlow.collectAsStateWithLifecycle(initialValue = null)

        CompositionLocalProvider(
            com.lomo.ui.util.LocalAnimatedVisibilityScope provides this,
        ) {
            val memoMenuCommandHandler =
                rememberMemoMenuCommandHandler(
                    presentationState =
                    MemoMenuPresentationState(
                        shareCardShowTime = appPreferences.shareCardShowTime,
                        shareCardShowSignature = appPreferences.shareCardShowBrand,
                        shareCardSignatureText = appPreferences.shareCardSignatureText,
                        customFontPath = appPreferences.customFontPath,
                    ),
                    onEditMemo = { memoId ->
                        mainViewModel.requestOpenMemo(memoId)
                        navController.popBackStackToMainOrNavigate()
                    },
                    onDeleteMemo = mainViewModel.deleteMemo,
                    onLanShare =
                    if (lanShareEnabled) {
                        { request -> navigateToShare(request.content, request.timestamp) }
                    } else {
                        null
                    },
                    contextAction =
                    MemoMenuContextAction.Jump { state ->
                        handleMemoJumpToMain(
                            selection = state,
                            requestFocusMemo = mainViewModel.requestFocusMemoInDefaultMainList,
                            navigateToMain = { navController.popBackStackToMainOrNavigate() },
                        )
                    },
                    onVersionHistory = { selection -> mainViewModel.loadVersionHistory(selection.memo) },
                )

            MemoMenuBinder(
                commandHandler = memoMenuCommandHandler,
            ) { showMenu ->
                GalleryReelScreen(
                    onTagClick = { tag -> navController.navigate(NavRoute.Tag(tag)) },
                    request = request,
                    viewerMode = GalleryReelMode.Gallery,
                    activeMemoDetail = activeMemoDetail,
                    onActiveMemoIdChanged = { memoId -> activeMemoId = memoId },
                    onBackClick = popBackStackSafely,
                    onTodoClick = mainViewModel.updateMemo,
                    onShowMenu = showMenu,
                )
            }
            com.lomo.app.feature.main.MainVersionHistoryEffectHost(
                bindings = mainViewModel.versionHistoryEffectBindings,
            )
        }
    }
}

private fun buildGalleryReelRequest(
    payload: GalleryReelPayloadStore.Payload,
    galleryItems: List<GalleryUiItem>,
    initialMemoIndex: Int,
    initialImageIndex: Int,
): GalleryReelRequest? {
    // Cold-start race guard: the gallery's UI flow seeds with an empty
    // list before the first DB emission. If the payload has memo ids but the
    // live list is still empty, treat it as "loading" and signal the caller
    // to wait — returning an empty request here would auto-pop the screen
    // before the user has a chance to see anything.
    if (galleryItems.isEmpty() && payload.memoIds.isNotEmpty()) return null

    val liveItemById = galleryItems.associateBy(GalleryUiItem::memoId)
    val orderedEntries =
        payload.memoIds
            .mapNotNull(liveItemById::get)
            .map { item ->
                GalleryReelEntry(
                    memoId = item.memoId,
                    imageUrls = item.imageUrls,
                )
            }.toImmutableList()
    val clampedMemoIndex =
        if (orderedEntries.isEmpty()) {
            0
        } else {
            initialMemoIndex.coerceIn(0, orderedEntries.lastIndex)
        }
    val clampedImageIndex =
        orderedEntries
            .getOrNull(clampedMemoIndex)
            ?.imageUrls
            ?.lastIndex
            ?.takeIf { lastIndex -> lastIndex >= 0 }
            ?.let { lastIndex -> initialImageIndex.coerceIn(0, lastIndex) }
            ?: 0

    return GalleryReelRequest(
        entries = orderedEntries,
        initialMemoIndex = clampedMemoIndex,
        initialImageIndex = clampedImageIndex,
    )
}

private fun NavHostController.popBackStackToMainOrNavigate() {
    if (!popBackStack(NavRoute.Main, inclusive = false)) {
        navigate(NavRoute.Main) {
            launchSingleTop = true
        }
    }
}
