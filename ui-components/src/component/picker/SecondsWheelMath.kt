package com.lomo.ui.component.picker

/**
 * Pure math for the seconds wheel.
 *
 * The wheel is rendered as a snap-flinging vertical [androidx.compose.foundation.lazy.LazyColumn]
 * where item index N represents the value N seconds. The list's content padding is configured so
 * that the visually-centered item equals the first visible index when the offset is zero. When the
 * offset is non-zero (mid-fling, mid-drag), the centered second is the first visible index rounded
 * toward the nearer neighbour.
 *
 * Kept as a separate object so the rounding/clamping contract can be pinned by a JVM unit test
 * without spinning up the Compose runtime.
 */
internal object SecondsWheelMath {
    const val MIN: Int = 0
    const val MAX: Int = 59
    const val ITEM_COUNT: Int = MAX - MIN + 1

    fun clamp(value: Int): Int = value.coerceIn(MIN, MAX)

    fun centeredSecond(
        firstVisibleIndex: Int,
        firstVisibleOffsetPx: Int,
        itemHeightPx: Int,
    ): Int {
        require(itemHeightPx > 0) { "itemHeightPx must be positive (was $itemHeightPx)" }
        val roundUp = firstVisibleOffsetPx * 2 >= itemHeightPx
        return clamp(firstVisibleIndex + if (roundUp) 1 else 0)
    }
}
