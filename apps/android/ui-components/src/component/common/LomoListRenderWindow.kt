package com.lomo.ui.component.common

/** One coordinate transform for retained rows, source accesses and placeholder ranks. */
class LomoListRenderWindow(
    sourceItemCount: Int,
    val startIndex: Int,
    sourceKeys: List<String>,
    renderKeys: List<String>,
) {
    private val sourceRanks = sourceKeys.mapIndexed { index, key -> key to startIndex + index }.toMap()
    private val renderedSourceRanks = renderKeys.map { sourceRanks[it] }
    private val retainedCount = renderKeys.size - sourceKeys.size
    val itemCount: Int = sourceItemCount + retainedCount

    init {
        require(startIndex >= 0 && sourceItemCount >= startIndex + sourceKeys.size) {
            "Source window must fit inside the source item count"
        }
        require(retainedCount >= 0 && renderKeys.toSet().containsAll(sourceKeys)) {
            "Exit presentation must retain every source row"
        }
    }

    fun sourceIndexAt(renderIndex: Int): Int? {
        if (renderIndex !in 0 until itemCount) return null
        return when {
            renderIndex < startIndex -> renderIndex
            renderIndex < startIndex + renderedSourceRanks.size -> renderedSourceRanks[renderIndex - startIndex]
            else -> renderIndex - retainedCount
        }
    }
}
