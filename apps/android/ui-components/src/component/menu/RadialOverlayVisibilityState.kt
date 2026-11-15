package com.lomo.ui.component.menu

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Owns open, dismissing, and closed lifecycle phases for radial sub-action overlays so exit
 * transitions finish before teardown and duplicate dismisses or item taps are ignored while exiting.
 */
@Stable
internal class RadialOverlayVisibilityState<T : Any> {
    var activeGroup by mutableStateOf<T?>(null)
        private set

    var targetVisible by mutableStateOf(false)
        private set

    private var generation = 0L
    private var pendingDismissToken: Long? = null

    val isDismissing: Boolean
        get() = activeGroup != null && !targetVisible

    val canInteract: Boolean
        get() = activeGroup != null && targetVisible

    fun open(group: T) {
        generation += 1L
        pendingDismissToken = null
        activeGroup = group
        targetVisible = true
    }

    fun requestDismiss(): Long? {
        if (!canInteract) return null
        val token = ++generation
        pendingDismissToken = token
        targetVisible = false
        return token
    }

    fun completeDismiss(token: Long) {
        if (pendingDismissToken == token && !targetVisible) {
            pendingDismissToken = null
            activeGroup = null
        }
    }

    fun settleActiveDismiss() {
        pendingDismissToken?.let(::completeDismiss)
    }

    fun completeImmediately() {
        generation += 1L
        pendingDismissToken = null
        targetVisible = false
        activeGroup = null
    }
}

/**
 * Drives radial spatial and effect animatables toward [visible] using [motionScheme] and invokes
 * [onDismissSettled] only after both exit channels reach rest.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal suspend fun runRadialOverlayTransition(
    visible: Boolean,
    spatialProgress: Animatable<Float, AnimationVector1D>,
    effectsAlpha: Animatable<Float, AnimationVector1D>,
    motionScheme: MotionScheme,
    onDismissSettled: () -> Unit,
) {
    val target = if (visible) 1f else 0f
    coroutineScope {
        launch { spatialProgress.animateTo(targetValue = target, animationSpec = motionScheme.defaultSpatialSpec()) }
        launch { effectsAlpha.animateTo(targetValue = target, animationSpec = motionScheme.defaultEffectsSpec()) }
    }
    if (!visible) {
        onDismissSettled()
    }
}
