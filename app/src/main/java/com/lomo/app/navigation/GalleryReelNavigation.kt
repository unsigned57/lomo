package com.lomo.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.lomo.app.feature.common.MemoActionOrderScopes
import com.lomo.app.feature.gallery.GalleryReelMode
import com.lomo.app.feature.gallery.GalleryReelRequest
import com.lomo.app.feature.gallery.GalleryReelScreen
import com.lomo.app.feature.main.MainViewModel
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.feature.memo.MemoMenuBinder
import com.lomo.app.feature.memo.MemoMenuPresentationState
import com.lomo.app.feature.memo.handleMemoJumpToMain
import com.lomo.app.feature.memo.rememberMemoMenuCommandHandler
import com.lomo.app.util.activityHiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

@Composable
internal fun rememberGalleryReelNavigationAction(
    navController: NavHostController,
    galleryMemos: ImmutableList<MemoUiModel>,
): (memoId: String, imageIndex: Int, aspectByMemoId: Map<String, Float>) -> Unit =
    remember(navController, galleryMemos) {
        { memoId, imageIndex, aspectByMemoId ->
            val memos = galleryMemos.filter { uiModel -> uiModel.imageUrls.isNotEmpty() }
            val initialMemoIndex = memos.indexOfFirst { uiModel -> uiModel.memo.id == memoId }
            val initialMemo = memos.getOrNull(initialMemoIndex)
            if (initialMemo != null) {
                val clampedImageIndex = imageIndex.coerceIn(0, initialMemo.imageUrls.lastIndex)
                val memoIds = memos.map { uiModel -> uiModel.memo.id }
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
    navController: NavHostController,
    popBackStackSafely: () -> Unit,
    navigateToShare: (String, Long) -> Unit,
    lanShareEnabled: Boolean,
) {
    composable<NavRoute.GalleryReel>(
        enterTransition = NavigationTransitions.imageViewerEnter,
        exitTransition = NavigationTransitions.imageViewerExit,
        popEnterTransition = NavigationTransitions.imageViewerPopEnter,
        popExitTransition = NavigationTransitions.imageViewerPopExit,
    ) { entry ->
        val route = entry.toRoute<NavRoute.GalleryReel>()
        val mainViewModel: MainViewModel = activityHiltViewModel()
        val galleryMemos by mainViewModel.galleryUiMemos.collectAsStateWithLifecycle()
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
            remember(payload, galleryMemos, route.initialMemoIndex, route.initialImageIndex) {
                buildGalleryReelRequest(
                    payload = payload,
                    galleryMemos = galleryMemos.toImmutableList(),
                    initialMemoIndex = route.initialMemoIndex,
                    initialImageIndex = route.initialImageIndex,
                )
            }

        if (request == null) {
            // Gallery memos are still loading after a cold start — wait for them
            // instead of treating the empty initial state as "memo removed" and
            // popping the route right away.
            return@composable
        }

        androidx.compose.runtime.CompositionLocalProvider(
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
                            showJump = true,
                            memoActionAutoReorderEnabled = appPreferences.memoActionAutoReorderEnabled,
                            memoActionOrder = appPreferences.memoActionOrderFor(MemoActionOrderScopes.GALLERY),
                        ),
                    onEditMemo = { memo ->
                        mainViewModel.requestOpenMemo(memo.id)
                        navController.popBackStackToMainOrNavigate()
                    },
                    onDeleteMemo = mainViewModel.deleteMemo,
                    onLanShare =
                        if (lanShareEnabled) {
                            { request -> navigateToShare(request.content, request.timestamp) }
                        } else {
                            null
                        },
                    onJump = { state ->
                        handleMemoJumpToMain(
                            selection = state,
                            requestFocusMemo = mainViewModel.requestFocusMemoInDefaultMainList,
                            navigateToMain = { navController.popBackStackToMainOrNavigate() },
                        )
                    },
                    onMemoActionInvoked = mainViewModel.recordGalleryMemoActionUsage,
                    onMemoActionOrderChanged = mainViewModel.updateGalleryMemoActionOrder,
                )

            MemoMenuBinder(
                commandHandler = memoMenuCommandHandler,
            ) { showMenu ->
                GalleryReelScreen(
                    request = request,
                    viewerMode = GalleryReelMode.Gallery,
                    dateFormat = appPreferences.dateFormat,
                    timeFormat = appPreferences.timeFormat,
                    onBackClick = popBackStackSafely,
                    onTodoClick = mainViewModel.updateMemo,
                    onShowMenu = showMenu,
                )
            }
        }
    }
}

private fun buildGalleryReelRequest(
    payload: GalleryReelPayloadStore.Payload,
    galleryMemos: ImmutableList<MemoUiModel>,
    initialMemoIndex: Int,
    initialImageIndex: Int,
): GalleryReelRequest? {
    // Cold-start race guard: the gallery's UI memo flow seeds with an empty
    // list before the first DB emission. If the payload has memo ids but the
    // live list is still empty, treat it as "loading" and signal the caller
    // to wait — returning an empty request here would auto-pop the screen
    // before the user has a chance to see anything.
    if (galleryMemos.isEmpty() && payload.memoIds.isNotEmpty()) return null

    val liveMemoById = galleryMemos.associateBy { uiModel -> uiModel.memo.id }
    val orderedMemos =
        payload.memoIds
            .mapNotNull(liveMemoById::get)
            .toImmutableList()
    val clampedMemoIndex =
        if (orderedMemos.isEmpty()) {
            0
        } else {
            initialMemoIndex.coerceIn(0, orderedMemos.lastIndex)
        }
    val clampedImageIndex =
        orderedMemos
            .getOrNull(clampedMemoIndex)
            ?.imageUrls
            ?.lastIndex
            ?.takeIf { lastIndex -> lastIndex >= 0 }
            ?.let { lastIndex -> initialImageIndex.coerceIn(0, lastIndex) }
            ?: 0

    return GalleryReelRequest(
        memos = orderedMemos,
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
