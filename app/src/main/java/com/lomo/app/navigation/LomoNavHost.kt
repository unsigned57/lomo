package com.lomo.app.navigation

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    viewModel: MainViewModel,
) {
    var lastBackNavigationTime by remember { mutableLongStateOf(0L) }
    val popBackStackSafely: () -> Unit = {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackNavigationTime >= BACK_NAVIGATION_THROTTLE_MILLIS) {
            lastBackNavigationTime = now
            navController.popBackStackOrNavigateMain()
        }
    }

    val navigateToShare: (String, Long) -> Unit = { content, timestamp ->
        val payloadKey = ShareRoutePayloadStore.putMemoContent(content)
        navController.navigate(
            NavRoute.Share(
                payloadKey = payloadKey,
                memoTimestamp = timestamp,
            ),
        )
    }

    val navigateToImage: (ImageViewerRequest) -> Unit = { request ->
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

    @OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
    androidx.compose.animation.SharedTransitionLayout {
        androidx.compose.runtime.CompositionLocalProvider(
            com.lomo.ui.util.LocalSharedTransitionScope provides this,
        ) {
            NavHost(
                navController = navController,
                startDestination = NavRoute.Main,
                enterTransition = NavigationTransitions.standardEnter,
                exitTransition = NavigationTransitions.standardExit,
                popEnterTransition = NavigationTransitions.standardPopEnter,
                popExitTransition = NavigationTransitions.standardPopExit,
            ) {
                composable<NavRoute.Main> {
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.lomo.ui.util.LocalAnimatedVisibilityScope provides this,
                    ) {
                        MainScreen(
                            viewModel = viewModel,
                            onNavigateToSettings = { navController.navigate(NavRoute.Settings) },
                            onNavigateToTrash = { navController.navigate(NavRoute.Trash) },
                            onNavigateToSearch = { navController.navigate(NavRoute.Search) },
                            onNavigateToTag = { tag -> navController.navigate(NavRoute.Tag(tag)) },
                            onNavigateToImage = navigateToImage,
                            onNavigateToDailyReview = { navController.navigate(NavRoute.DailyReview) },
                            onNavigateToGallery = { navController.navigate(NavRoute.Gallery) },
                            onNavigateToShare = navigateToShare,
                        )
                    }
                }

                composable<NavRoute.Settings> {
                    SettingsScreen(
                        onBackClick = popBackStackSafely,
                    )
                }

                composable<NavRoute.Trash> {
                    TrashScreen(
                        onBackClick = popBackStackSafely,
                    )
                }

                composable<NavRoute.Search> {
                    SearchScreen(
                        onBackClick = popBackStackSafely,
                        onNavigateToShare = navigateToShare,
                    )
                }

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
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.lomo.ui.util.LocalAnimatedVisibilityScope provides this,
                    ) {
                        com.lomo.app.feature.review.DailyReviewScreen(
                            onBackClick = popBackStackSafely,
                            onNavigateToImage = navigateToImage,
                            onNavigateToShare = navigateToShare,
                            onNavigateToMemo = { memoId ->
                                viewModel.requestFocusMemo(memoId)
                                navController.popBackStackOrNavigateMain()
                            },
                        )
                    }
                }

                composable<NavRoute.Gallery> {
                    GalleryScreen(
                        viewModel = viewModel,
                        onBackClick = popBackStackSafely,
                        onNavigateToImage = navigateToImage,
                        onNavigateToShare = navigateToShare,
                    )
                }

                composable<NavRoute.Share> {
                    ShareScreen(
                        onBackClick = popBackStackSafely,
                    )
                }

                // ImageViewer with custom fade + scale animation
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
                            ImageViewerRoutePayloadStore.getImageUrls(route.payloadKey)
                                ?.ifEmpty { null }
                                ?: decodedUrl.takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
                        }
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.lomo.ui.util.LocalAnimatedVisibilityScope provides this,
                    ) {
                        ImageViewerScreen(
                            imageUrls = imageUrls,
                            initialIndex = route.initialIndex,
                            onBackClick = popBackStackSafely,
                        )
                    }
                }
            }
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
