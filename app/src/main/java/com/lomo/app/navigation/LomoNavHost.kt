package com.lomo.app.navigation

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.lomo.app.feature.gallery.GalleryScreen
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.image.ImageViewerScreen
import com.lomo.app.feature.main.MainScreen
import com.lomo.app.feature.main.MainViewModel
import com.lomo.app.feature.search.SearchScreen
import com.lomo.app.feature.settings.SettingsScreen
import com.lomo.app.feature.share.ShareScreen
import com.lomo.app.feature.tag.TagFilterScreen
import com.lomo.app.feature.trash.TrashScreen
import com.lomo.app.util.activityHiltViewModel
import kotlinx.collections.immutable.toImmutableList
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private const val BACK_NAVIGATION_THROTTLE_MILLIS = 500L

/**
 * Main navigation host for the Memos app.
 * Extracted from MainActivity to improve maintainability.
 */
@Composable
fun LomoNavHost(
    navController: NavHostController,
) {
    val popBackStackSafely = rememberBackNavigationAction(navController = navController)
    val navigateToShare = rememberShareNavigationAction(navController = navController)
    val navigateToImage = rememberImageNavigationAction(navController = navController)

    @OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
    androidx.compose.animation.SharedTransitionLayout {
        androidx.compose.runtime.CompositionLocalProvider(
            com.lomo.ui.util.LocalSharedTransitionScope provides this,
        ) {
            LomoNavigationGraph(
                navController = navController,
                popBackStackSafely = popBackStackSafely,
                navigateToShare = navigateToShare,
                navigateToImage = navigateToImage,
            )
        }
    }
}

@Composable
private fun rememberBackNavigationAction(navController: NavHostController): () -> Unit {
    var lastBackNavigationTime by remember { mutableLongStateOf(0L) }

    return remember(navController) {
        {
            val now = SystemClock.elapsedRealtime()
            if (now - lastBackNavigationTime >= BACK_NAVIGATION_THROTTLE_MILLIS) {
                lastBackNavigationTime = now
                navController.popBackStackOrNavigateMain()
            }
        }
    }
}

@Composable
private fun rememberShareNavigationAction(navController: NavHostController): (String, Long) -> Unit =
    remember(navController) {
        { content, timestamp ->
            val payloadKey = ShareRoutePayloadStore.putMemoContent(content)
            navController.navigate(
                NavRoute.Share(
                    payloadKey = payloadKey,
                    memoTimestamp = timestamp,
                ),
            )
        }
    }

@Composable
private fun rememberImageNavigationAction(navController: NavHostController): (ImageViewerRequest) -> Unit =
    remember(navController) {
        { request ->
            val imageUrls = request.imageUrls.ifEmpty { emptyList() }
            val clampedIndex =
                if (imageUrls.isEmpty()) {
                    0
                } else {
                    request.initialIndex.coerceIn(0, imageUrls.lastIndex)
                }
            val fallbackUrl = imageUrls.getOrNull(clampedIndex).orEmpty()
            val encodedFallbackUrl = URLEncoder.encode(fallbackUrl, StandardCharsets.UTF_8.toString())
            val payloadKey = ImageViewerRoutePayloadStore.putImageUrls(imageUrls)
            navController.navigate(
                NavRoute.ImageViewer(
                    url = encodedFallbackUrl,
                    payloadKey = payloadKey,
                    initialIndex = clampedIndex,
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
) {
    NavHost(
        navController = navController,
        startDestination = NavRoute.Main,
        enterTransition = NavigationTransitions.standardEnter,
        exitTransition = NavigationTransitions.standardExit,
        popEnterTransition = NavigationTransitions.standardPopEnter,
        popExitTransition = NavigationTransitions.standardPopExit,
    ) {
        addPrimaryDestinations(
            navController = navController,
            popBackStackSafely = popBackStackSafely,
            navigateToShare = navigateToShare,
            navigateToImage = navigateToImage,
        )
        addSecondaryDestinations(
            navController = navController,
            popBackStackSafely = popBackStackSafely,
            navigateToShare = navigateToShare,
            navigateToImage = navigateToImage,
        )
        addImageViewerDestination(
            popBackStackSafely = popBackStackSafely,
        )
    }
}

private fun NavGraphBuilder.addPrimaryDestinations(
    navController: NavHostController,
    popBackStackSafely: () -> Unit,
    navigateToShare: (String, Long) -> Unit,
    navigateToImage: (ImageViewerRequest) -> Unit,
) {
    composable<NavRoute.Main> {
        androidx.compose.runtime.CompositionLocalProvider(
            com.lomo.ui.util.LocalAnimatedVisibilityScope provides this,
        ) {
            MainScreen(
                onNavigateToSettings = { navController.navigate(NavRoute.Settings) },
                onNavigateToTrash = { navController.navigate(NavRoute.Trash) },
                onNavigateToSearch = { navController.navigate(NavRoute.Search) },
                onNavigateToTag = { tag -> navController.navigate(NavRoute.Tag(tag)) },
                onNavigateToImage = navigateToImage,
                onNavigateToDailyReview = { navController.navigate(NavRoute.DailyReview) },
                onNavigateToGallery = { navController.navigate(NavRoute.Gallery) },
                onNavigateToStatistics = { navController.navigate(NavRoute.Statistics) },
                onNavigateToShare = navigateToShare,
            )
        }
    }

    composable<NavRoute.Settings> {
        SettingsScreen(onBackClick = popBackStackSafely)
    }

    composable<NavRoute.Trash> {
        TrashScreen(onBackClick = popBackStackSafely)
    }

    composable<NavRoute.Search> {
        SearchScreen(
            onBackClick = popBackStackSafely,
            onNavigateToShare = navigateToShare,
        )
    }
}

private fun NavGraphBuilder.addSecondaryDestinations(
    navController: NavHostController,
    popBackStackSafely: () -> Unit,
    navigateToShare: (String, Long) -> Unit,
    navigateToImage: (ImageViewerRequest) -> Unit,
) {
    composable<NavRoute.Tag> { backStackEntry ->
        val tag = backStackEntry.toRoute<NavRoute.Tag>()
        androidx.compose.runtime.CompositionLocalProvider(
            com.lomo.ui.util.LocalAnimatedVisibilityScope provides this,
        ) {
            TagFilterScreen(
                tagName = tag.tagName,
                onBackClick = popBackStackSafely,
                onNavigateToImage = navigateToImage,
                onNavigateToShare = navigateToShare,
            )
        }
    }

    composable<NavRoute.DailyReview> {
        val mainViewModel: MainViewModel = activityHiltViewModel()
        androidx.compose.runtime.CompositionLocalProvider(
            com.lomo.ui.util.LocalAnimatedVisibilityScope provides this,
        ) {
            com.lomo.app.feature.review.DailyReviewScreen(
                onBackClick = popBackStackSafely,
                onNavigateToImage = navigateToImage,
                onNavigateToShare = navigateToShare,
                onNavigateToMemo = { memoId ->
                    mainViewModel.requestFocusMemo(memoId)
                    navController.popBackStackOrNavigateMain()
                },
            )
        }
    }

    composable<NavRoute.Gallery> {
        GalleryScreen(
            onBackClick = popBackStackSafely,
            onNavigateToImage = navigateToImage,
            onNavigateToShare = navigateToShare,
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

private fun NavGraphBuilder.addImageViewerDestination(popBackStackSafely: () -> Unit) {
    composable<NavRoute.ImageViewer>(
        enterTransition = NavigationTransitions.imageViewerEnter,
        exitTransition = NavigationTransitions.imageViewerExit,
        popEnterTransition = NavigationTransitions.imageViewerPopEnter,
        popExitTransition = NavigationTransitions.imageViewerPopExit,
    ) { entry ->
        val route = entry.toRoute<NavRoute.ImageViewer>()
        val decodedUrl = URLDecoder.decode(route.url, StandardCharsets.UTF_8.toString())
        val imageUrls =
            androidx.compose.runtime.remember(route.payloadKey, decodedUrl) {
                ImageViewerRoutePayloadStore
                    .getImageUrls(route.payloadKey)
                    ?.ifEmpty { null }
                    ?: decodedUrl.takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
            }
        androidx.compose.runtime.CompositionLocalProvider(
            com.lomo.ui.util.LocalAnimatedVisibilityScope provides this,
        ) {
            ImageViewerScreen(
                imageUrls = imageUrls.toImmutableList(),
                initialIndex = route.initialIndex,
                onBackClick = popBackStackSafely,
            )
        }
    }
}

private fun NavHostController.popBackStackOrNavigateMain() {
    if (!popBackStack()) {
        navigate(NavRoute.Main) {
            launchSingleTop = true
        }
    }
}
