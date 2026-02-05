package com.lomo.app.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.lomo.app.feature.image.ImageViewerScreen
import com.lomo.app.feature.main.MainScreen
import com.lomo.app.feature.main.MainViewModel
import com.lomo.app.feature.search.SearchScreen
import com.lomo.app.feature.settings.SettingsScreen
import com.lomo.app.feature.tag.TagFilterScreen
import com.lomo.app.feature.trash.TrashScreen
import com.lomo.ui.theme.MotionTokens
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
    val enterTransition = {
        slideInHorizontally(
            initialOffsetX = { (it * 0.15f).toInt() },
            animationSpec =
                tween(
                    durationMillis = MotionTokens.DurationLong2,
                    easing = MotionTokens.EasingEmphasizedDecelerate,
                ),
        ) +
            fadeIn(
                animationSpec =
                    tween(
                        durationMillis = MotionTokens.DurationLong2,
                    ),
            ) +
            scaleIn(
                initialScale = 0.95f,
                animationSpec =
                    tween(
                        durationMillis = MotionTokens.DurationLong2,
                        easing = MotionTokens.EasingEmphasizedDecelerate,
                    ),
            )
    }

    val exitTransition = {
        slideOutHorizontally(
            targetOffsetX = { -(it * 0.15f).toInt() },
            animationSpec =
                tween(
                    durationMillis = MotionTokens.DurationLong2,
                    easing = MotionTokens.EasingEmphasizedAccelerate,
                ),
        ) +
            fadeOut(
                animationSpec =
                    tween(
                        durationMillis = MotionTokens.DurationLong2,
                    ),
            ) +
            scaleOut(
                targetScale = 1.05f,
                animationSpec =
                    tween(
                        durationMillis = MotionTokens.DurationLong2,
                        easing = MotionTokens.EasingEmphasizedAccelerate,
                    ),
            )
    }

    val popEnterTransition = {
        slideInHorizontally(
            initialOffsetX = { -(it * 0.15f).toInt() },
            animationSpec =
                tween(
                    durationMillis = MotionTokens.DurationLong2,
                    easing = MotionTokens.EasingEmphasizedDecelerate,
                ),
        ) +
            fadeIn(
                animationSpec =
                    tween(
                        durationMillis = MotionTokens.DurationLong2,
                    ),
            ) +
            scaleIn(
                initialScale = 1.05f,
                animationSpec =
                    tween(
                        durationMillis = MotionTokens.DurationLong2,
                        easing = MotionTokens.EasingEmphasizedDecelerate,
                    ),
            )
    }

    val popExitTransition = {
        slideOutHorizontally(
            targetOffsetX = { (it * 0.15f).toInt() },
            animationSpec =
                tween(
                    durationMillis = MotionTokens.DurationLong2,
                    easing = MotionTokens.EasingEmphasizedAccelerate,
                ),
        ) +
            fadeOut(
                animationSpec =
                    tween(
                        durationMillis = MotionTokens.DurationLong2,
                    ),
            ) +
            scaleOut(
                targetScale = 0.95f,
                animationSpec =
                    tween(
                        durationMillis = MotionTokens.DurationLong2,
                        easing = MotionTokens.EasingEmphasizedAccelerate,
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
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
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
                            onNavigateToMemo = { _, _ -> },
                            onNavigateToTag = { tag -> navController.navigate(NavRoute.Tag(tag)) },
                            onNavigateToImage = { url ->
                                val encoded = URLEncoder.encode(url, StandardCharsets.UTF_8.toString())
                                navController.navigate(NavRoute.ImageViewer(encoded))
                            },
                            onNavigateToDailyReview = { navController.navigate(NavRoute.DailyReview) },
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
                        )
                    }
                }

                // ImageViewer with custom fade + scale animation
                composable<NavRoute.ImageViewer>(
                    enterTransition = {
                        fadeIn(
                            animationSpec =
                                tween(
                                    durationMillis = MotionTokens.DurationMedium2,
                                    easing = MotionTokens.EasingEmphasizedDecelerate,
                                ),
                        ) +
                            scaleIn(
                                initialScale = 0.92f,
                                animationSpec =
                                    tween(
                                        durationMillis = MotionTokens.DurationMedium2,
                                        easing = MotionTokens.EasingEmphasizedDecelerate,
                                    ),
                            )
                    },
                    exitTransition = {
                        fadeOut(
                            animationSpec =
                                tween(
                                    durationMillis = MotionTokens.DurationShort4,
                                    easing = MotionTokens.EasingEmphasizedAccelerate,
                                ),
                        ) +
                            scaleOut(
                                targetScale = 1.05f,
                                animationSpec =
                                    tween(
                                        durationMillis = MotionTokens.DurationShort4,
                                        easing = MotionTokens.EasingEmphasizedAccelerate,
                                    ),
                            )
                    },
                    popEnterTransition = {
                        fadeIn(
                            animationSpec =
                                tween(
                                    durationMillis = MotionTokens.DurationMedium1,
                                ),
                        )
                    },
                    popExitTransition = {
                        fadeOut(
                            animationSpec =
                                tween(
                                    durationMillis = MotionTokens.DurationMedium2,
                                    easing = MotionTokens.EasingEmphasizedAccelerate,
                                ),
                        ) +
                            scaleOut(
                                targetScale = 0.92f,
                                animationSpec =
                                    tween(
                                        durationMillis = MotionTokens.DurationMedium2,
                                        easing = MotionTokens.EasingEmphasizedAccelerate,
                                    ),
                            )
                    },
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
