package com.lomo.app.feature.main

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Stable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** One gesture drives the indicator and content; content returns on release, including during refresh. */
@OptIn(ExperimentalMaterial3Api::class)
@Stable
internal class MemoListPullToRefreshState(
    private val indicator: PullToRefreshState,
    private val settleSpec: FiniteAnimationSpec<Float>,
    private val isRefreshing: () -> Boolean,
) : PullToRefreshState by indicator {
    private val contentDistance = Animatable(0f)
    override val isAnimating: Boolean get() = indicator.isAnimating || contentDistance.isRunning
    val contentDistanceFraction: Float get() = contentDistance.value

    override suspend fun snapTo(targetValue: Float) = coroutineScope {
        launch { indicator.snapTo(targetValue) }
        // During refresh the framework can re-publish the ball's held threshold on scroll.
        // That distance belongs to the indicator; it is not another content pull.
        contentDistance.snapTo(if (isRefreshing()) 0f else targetValue)
    }

    override suspend fun animateToThreshold() = settle { indicator.animateToThreshold() }

    override suspend fun animateToHidden() = settle { indicator.animateToHidden() }

    private suspend fun settle(updateIndicator: suspend () -> Unit) = coroutineScope {
        launch { updateIndicator() }
        contentDistance.animateTo(0f, settleSpec)
        Unit
    }
}
