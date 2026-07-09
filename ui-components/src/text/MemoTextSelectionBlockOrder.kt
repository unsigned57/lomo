package com.lomo.ui.text

/**
 * Resolves the live block order used by [MemoMultiParagraphSelection] from a registration
 * snapshot. Compose does not guarantee that `onGloballyPositioned` fires in layout order
 * across sibling composables, so relying on insertion order alone produces a `blockOrder`
 * that disagrees with the on-screen vertical layout — and a cross-paragraph selection
 * then resolves "middle" blocks as out-of-range, erasing their highlight.
 *
 * Blocks are sorted by their resolved Y coordinate (lowest first = topmost on screen).
 * Stable order is preserved when two blocks share the same Y. Blocks that haven't been
 * measured yet (no entry in [yByKey]) keep their registration position relative to each
 * other and land after all measured blocks so they don't disrupt active highlights while
 * Compose is still settling layout.
 */
internal fun resolveMemoBlockOrderByY(
    registrationOrder: List<Any>,
    yByKey: Map<Any, Float>,
): List<Any> {
    if (registrationOrder.isEmpty()) return emptyList()
    val measured = mutableListOf<Pair<Any, Float>>()
    val unmeasured = mutableListOf<Any>()
    registrationOrder.forEach { key ->
        val y = yByKey[key]
        if (y != null) {
            measured.add(key to y)
        } else {
            unmeasured.add(key)
        }
    }
    measured.sortBy { it.second }
    return measured.map { it.first } + unmeasured
}
