package com.lomo.ui.component.input

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.DragScope
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.coroutineScope

/**
 * The single owner of the compact ⇄ long-form morph.
 *
 * Every piece of sheet geometry and chrome is a function of [fraction] (0 = compact card, 1 = long
 * form). The toggle, back and the drag handle all move this one value through one mutex, so a new
 * gesture or target interrupts the running motion and continues from where it is — with its
 * velocity — instead of restarting a second, independently timed animation.
 */
@Stable
internal class InputSheetExpansionState(expanded: Boolean) : DraggableState {
    var fraction by mutableFloatStateOf(if (expanded) 1f else 0f)
        private set

    /**
     * Pixels a drag travels between the forms, published by the panel layout. Zero until the sheet has
     * been measured, and a drag over an unmeasured sheet has no defined travel, so it does not move it.
     */
    var dragSpanPx: Float = 0f

    /** Fraction per second of the latest frame; an interrupting animation continues from it. */
    private var velocity = 0f
    private val mutex = MutatorMutex()
    private val dragScope =
        object : DragScope {
            override fun dragBy(pixels: Float) = applyDrag(pixels)
        }

    suspend fun animateTo(expanded: Boolean, animationSpec: AnimationSpec<Float>, initialVelocity: Float = velocity) {
        val target = if (expanded) 1f else 0f
        mutex.mutate {
            animate(
                initialValue = fraction,
                targetValue = target,
                initialVelocity = initialVelocity,
                animationSpec = animationSpec,
            ) { value, frameVelocity ->
                fraction = value
                velocity = frameVelocity
            }
            velocity = 0f
        }
    }

    /** Converts a released drag velocity (px/s, upward negative) into fraction per second. */
    fun fractionVelocityOf(dragVelocityPxPerSecond: Float): Float =
        if (dragSpanPx > 0f) -dragVelocityPxPerSecond / dragSpanPx else 0f

    override fun dispatchRawDelta(delta: Float) = applyDrag(delta)

    override suspend fun drag(dragPriority: MutatePriority, block: suspend DragScope.() -> Unit) {
        coroutineScope { mutex.mutateWith(dragScope, dragPriority, block) }
    }

    /** Dragging up (a negative delta) opens the long form; the finger owns the value until release. */
    private fun applyDrag(deltaPx: Float) {
        val span = dragSpanPx
        if (span <= 0f) return
        velocity = 0f
        fraction = (fraction - deltaPx / span).coerceIn(0f, 1f)
    }
}
