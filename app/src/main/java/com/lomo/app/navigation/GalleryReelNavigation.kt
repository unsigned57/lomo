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
import com.lomo.app.feature.gallery.GalleryReelRequest
import com.lomo.app.feature.gallery.GalleryReelScreen
import com.lomo.app.feature.main.MainViewModel
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.feature.memo.MemoMenuBinder
import com.lomo.app.feature.memo.handleMemoJumpToMain
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

        androidx.compose.runtime.CompositionLocalProvider(
            com.lomo.ui.util.LocalAnimatedVisibilityScope provides this,
        ) {
            MemoMenuBinder(
                shareCardShowTime = appPreferences.shareCardShowTime,
                shareCardShowSignature = appPreferences.shareCardShowBrand,
                shareCardSignatureText = appPreferences.shareCardSignatureText,
                onEditMemo = { memo ->
                    mainViewModel.requestOpenMemo(memo.id)
                    navController.popBackStackToMainOrNavigate()
                },
                onDeleteMemo = mainViewModel.deleteMemo,
                onLanShare = if (lanShareEnabled) navigateToShare else null,
                onJump = { state ->
                    handleMemoJumpToMain(
                        state = state,
                        requestFocusMemo = mainViewModel.requestFocusMemoInDefaultMainList,
                        navigateToMain = { navController.popBackStackToMainOrNavigate() },
                    )
                },
                showJump = true,
                memoActionAutoReorderEnabled = appPreferences.memoActionAutoReorderEnabled,
                memoActionOrder = appPreferences.memoActionOrderFor(MemoActionOrderScopes.GALLERY),
                onMemoActionInvoked = mainViewModel.recordGalleryMemoActionUsage,
                onMemoActionOrderChanged = mainViewModel.updateGalleryMemoActionOrder,
            ) { showMenu ->
                GalleryReelScreen(
                    request = request,
                    dateFormat = appPreferences.dateFormat,
                    timeFormat = appPreferences.timeFormat,
                    onBackClick = popBackStackSafely,
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
): GalleryReelRequest {
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

