package com.lomo.app.navigation

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController

private const val BACK_NAVIGATION_THROTTLE_MILLIS = 500L

@Composable
internal fun rememberBackNavigationAction(navController: NavHostController): () -> Unit {
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

internal fun NavHostController.popBackStackOrNavigateMain() {
    val currentRoute = currentDestination?.route
    val popped = popBackStack()
    if (shouldNavigateToMain(currentRoute = currentRoute, popBackStackSucceeded = popped)) {
        navigate(NavRoute.Main) {
            launchSingleTop = true
        }
    }
}

internal fun shouldNavigateToMain(
    currentRoute: String?,
    popBackStackSucceeded: Boolean,
): Boolean = currentRoute != NavRouteSerialNames.MAIN && !popBackStackSucceeded
