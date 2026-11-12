package com.lomo.ui.component.navigation

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.lomo.ui.theme.LocalLomoMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext

/**
 * Validated progress of one predictive back gesture. A gesture aborted by cancellation runs
 * [onCancel] (non-cancellable) to restore the preview, and the cancellation still propagates.
 * Callers collect this flow inside `PredictiveBackHandler` and commit only after collection
 * completes normally, so only a completed gesture can navigate.
 */
fun Flow<Float>.predictiveBackProgress(onCancel: suspend () -> Unit): Flow<Float> = onCompletion { cause ->
    // The completion cause itself is the discriminator: an aborted gesture restores the
    // preview even when the cancelling exception came from the flow rather than the job.
    if (cause is CancellationException) {
        withContext(NonCancellable) { onCancel() }
    }
}.map { fraction ->
    require(fraction.isFinite() && fraction in 0f..1f) { "Invalid predictive back progress" }
    fraction
}

/** A custom surface can preview returning without changing its navigation or draft state. */
@Composable
fun rememberPredictiveBackProgress(enabled: Boolean, onBack: () -> Unit): State<Float> {
    val progress = remember { Animatable(0f) }
    val currentOnBack = rememberUpdatedState(onBack)
    // Back-preview settle is component feedback, so it resolves through the expressive scheme.
    val scheme = LocalLomoMotion.current.component
    PredictiveBackHandler(enabled = enabled) { events ->
        events
            .map { it.progress }
            .predictiveBackProgress(onCancel = { progress.animateTo(0f, scheme.fastSpatialSpec()) })
            .collect { progress.snapTo(it) }
        currentOnBack.value()
        progress.animateTo(0f, scheme.fastSpatialSpec())
    }
    return progress.asState()
}
