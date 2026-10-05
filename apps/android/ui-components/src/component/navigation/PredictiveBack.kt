package com.lomo.ui.component.navigation

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext

/** Only a completed gesture commits. Cancellation restores the preview and propagates upstream. */
suspend fun consumePredictiveBackGesture(
    progress: Flow<Float>,
    onProgress: suspend (Float) -> Unit,
    onCommit: suspend () -> Unit,
    onCancel: suspend () -> Unit,
) {
    progress
        .onCompletion { cause ->
            // The completion cause itself is the discriminator: an aborted gesture restores
            // the preview even when the cancelling exception came from the flow rather than
            // the enclosing job.
            if (cause is CancellationException) {
                withContext(NonCancellable) { onCancel() }
            }
        }.collect { fraction ->
            require(fraction.isFinite() && fraction in 0f..1f) { "Invalid predictive back progress" }
            onProgress(fraction)
        }
    onCommit()
}

/** A custom surface can preview returning without changing its navigation or draft state. */
@Composable
fun rememberPredictiveBackProgress(enabled: Boolean, onBack: () -> Unit): State<Float> {
    val progress = remember { Animatable(0f) }
    val currentOnBack = rememberUpdatedState(onBack)
    val scheme = MaterialTheme.motionScheme
    PredictiveBackHandler(enabled = enabled) { events ->
        consumePredictiveBackGesture(
            progress = events.map { it.progress },
            onProgress = { progress.snapTo(it) },
            onCommit = {
                currentOnBack.value()
                progress.animateTo(0f, scheme.fastSpatialSpec())
            },
            onCancel = { progress.animateTo(0f, scheme.fastSpatialSpec()) },
        )
    }
    return progress.asState()
}
