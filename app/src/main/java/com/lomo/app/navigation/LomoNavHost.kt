package com.lomo.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.lomo.app.feature.gallery.GalleryScreen
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

/**
 * Main navigation host for the Memos app.
 * Extracted from MainActivity to improve maintainability.
 */
@Composable
fun LomoNavHost(
    navController: NavHostController,
    viewModel: MainViewModel,
) {
    val navigateToShare: (String, Long) -> Unit = { content, timestamp ->
        val payloadKey = ShareRoutePayloadStore.putMemoContent(content)
        navController.navigate(
            NavRoute.Share(
                payloadKey = payloadKey,
                memoTimestamp = timestamp,
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
                            onNavigateToImage = { url ->
                                val encoded = URLEncoder.encode(url, StandardCharsets.UTF_8.toString())
                                navController.navigate(NavRoute.ImageViewer(encoded))
                            },
                            onNavigateToDailyReview = { navController.navigate(NavRoute.DailyReview) },
                            onNavigateToGallery = { navController.navigate(NavRoute.Gallery) },
                            onNavigateToShare = navigateToShare,
                        )
                    }
                }

                composable<NavRoute.Settings> {
                    SettingsScreen(
                        onBackClick = { navController.popBackStack() },
                    )
                }

                composable<NavRoute.Trash> {
                    TrashScreen(
                        onBackClick = { navController.popBackStack() },
                    )
                }

                composable<NavRoute.Search> {
                    SearchScreen(
                        onBackClick = { navController.popBackStack() },
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
                            onBackClick = { navController.popBackStack() },
                            onNavigateToImage = { url ->
                                val encoded = URLEncoder.encode(url, StandardCharsets.UTF_8.toString())
                                navController.navigate(NavRoute.ImageViewer(encoded))
                            },
                            onNavigateToShare = navigateToShare,
                        )
                    }
                }

                composable<NavRoute.DailyReview> {
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.lomo.ui.util.LocalAnimatedVisibilityScope provides this,
                    ) {
                        com.lomo.app.feature.review.DailyReviewScreen(
                            onBackClick = { navController.popBackStack() },
                            onNavigateToImage = { url ->
                                val encoded = URLEncoder.encode(url, StandardCharsets.UTF_8.toString())
                                navController.navigate(NavRoute.ImageViewer(encoded))
                            },
                            onNavigateToShare = navigateToShare,
                        )
                    }
                }

                composable<NavRoute.Gallery> {
                    GalleryScreen(
                        viewModel = viewModel,
                        onBackClick = { navController.popBackStack() },
                        onNavigateToImage = { url ->
                            val encoded = URLEncoder.encode(url, StandardCharsets.UTF_8.toString())
                            navController.navigate(NavRoute.ImageViewer(encoded))
                        },
                    )
                }

                composable<NavRoute.Share> {
                    ShareScreen(
                        onBackClick = { navController.popBackStack() },
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
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.lomo.ui.util.LocalAnimatedVisibilityScope provides this,
                    ) {
                        ImageViewerScreen(
                            url = decodedUrl,
                            onBackClick = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}
