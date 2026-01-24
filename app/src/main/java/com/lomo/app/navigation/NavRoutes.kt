
package com.lomo.app.navigation

import kotlinx.serialization.Serializable

@Serializable
sealed interface NavRoute {
    @Serializable
    data object Main : NavRoute

    @Serializable
    data object Settings : NavRoute

    @Serializable
    data object Trash : NavRoute

    @Serializable
    data object Search : NavRoute

    @Serializable
    data class Tag(
        val tagName: String,
    ) : NavRoute

    @Serializable
    data class ImageViewer(
        val url: String,
    ) : NavRoute

    @Serializable
    data object DailyReview : NavRoute
}
