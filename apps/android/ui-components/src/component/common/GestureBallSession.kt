package com.lomo.ui.component.common

import com.lomo.domain.model.QuickBallGesture
import kotlin.math.abs
import kotlin.math.hypot

/** One pointer sequence. Movement is remembered so returning to center cancels instead of tapping. */
class GestureBallSession(
    private val touchSlop: Float,
    private val swipeThreshold: Float,
    private val longPressTimeoutMillis: Long,
) {
    init {
        require(touchSlop > 0 && swipeThreshold >= touchSlop && longPressTimeoutMillis > 0)
    }

    private var moved = false
    private var finished = false
    private var candidate: QuickBallGesture? = null

    fun update(x: Float, y: Float, elapsedMillis: Long): QuickBallGesture? {
        require(x.isFinite() && y.isFinite() && elapsedMillis >= 0)
        if (finished) return null
        moved = moved || hypot(x, y) > touchSlop
        val distance = hypot(x, y)
        val direction = when {
            abs(x) > abs(y) * DIRECTION_DOMINANCE ->
                if (x > 0) QuickBallGesture.RIGHT else QuickBallGesture.LEFT

            abs(y) > abs(x) * DIRECTION_DOMINANCE ->
                if (y > 0) QuickBallGesture.DOWN else QuickBallGesture.UP

            else -> null
        }
        // A smaller release radius absorbs finger jitter without guessing a new direction.
        val requiredDistance = if (direction != null && direction == candidate) {
            swipeThreshold * SWIPE_RELEASE_FRACTION
        } else {
            swipeThreshold
        }
        candidate = when {
            direction != null && distance >= requiredDistance -> direction
            !moved && elapsedMillis >= longPressTimeoutMillis -> QuickBallGesture.LONG_PRESS
            else -> null
        }
        return candidate
    }

    fun release(): QuickBallGesture? {
        if (finished) return null
        finished = true
        return candidate ?: if (!moved) QuickBallGesture.TAP else null
    }

    fun cancel() {
        finished = true
        candidate = null
    }
}

private const val DIRECTION_DOMINANCE = 1.25f
private const val SWIPE_RELEASE_FRACTION = 0.75f
