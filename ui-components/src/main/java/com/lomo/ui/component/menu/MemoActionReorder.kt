package com.lomo.ui.component.menu

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList

internal const val DRAG_SCALE_FACTOR = 1.05f
internal const val DRAG_ALPHA = 0.92f

internal class ActionReorderState {
    var draggedId: MemoActionId? by mutableStateOf(null)
        private set

    var dragOffset: Float by mutableFloatStateOf(0f)
        private set

    val isDragging: Boolean get() = draggedId != null

    val itemBounds = mutableStateMapOf<MemoActionId, ItemBounds>()

    val swapAnimationOffsets = mutableStateMapOf<MemoActionId, Float>()

    private var viewportWidth: Int = 0

    fun setViewportWidth(width: Int) {
        viewportWidth = width
    }

    fun startDrag(id: MemoActionId) {
        draggedId = id
        dragOffset = 0f
        swapAnimationOffsets.clear()
    }

    fun updateDrag(deltaX: Float) {
        val newOffset = dragOffset + deltaX
        if (viewportWidth > 0) {
            val bounds = draggedId?.let { itemBounds[it] } ?: return
            val itemWidth = bounds.right - bounds.left
            val maxOffset = viewportWidth.toFloat()
            dragOffset = newOffset.coerceIn(-maxOffset, maxOffset - itemWidth.toFloat() / 2)
        } else {
            dragOffset = newOffset
        }
    }

    fun endDrag() {
        draggedId = null
        dragOffset = 0f
        swapAnimationOffsets.clear()
    }

    fun checkAndSwap(actions: SnapshotStateList<MemoActionSheetAction>) {
        val dragged = draggedId ?: return
        val draggedBounds = itemBounds[dragged] ?: return
        val draggedCenter = (draggedBounds.left + draggedBounds.right) / 2f + dragOffset
        val draggedIndex = actions.indexOfFirst { it.id == dragged }
        if (draggedIndex < 0) return

        val target = findSwapTarget(actions, dragged, draggedCenter, draggedIndex)
        if (target != null) {
            val (targetIndex, targetBounds) = target
            val targetId = actions[targetIndex].id
            val draggedWidth = draggedBounds.right - draggedBounds.left
            val targetWidth = targetBounds.right - targetBounds.left
            val adjustment =
                if (targetIndex > draggedIndex) {
                    (targetWidth - draggedWidth).toFloat()
                } else {
                    -(targetWidth - draggedWidth).toFloat()
                }
            if (targetId != null) {
                val displacement =
                    if (targetIndex > draggedIndex) {
                        -(draggedWidth.toFloat())
                    } else {
                        draggedWidth.toFloat()
                    }
                swapAnimationOffsets[targetId] = displacement
            }
            actions.add(targetIndex, actions.removeAt(draggedIndex))
            dragOffset += adjustment
        }
    }

    private fun findSwapTarget(
        actions: SnapshotStateList<MemoActionSheetAction>,
        draggedId: MemoActionId,
        draggedCenter: Float,
        draggedIndex: Int,
    ): Pair<Int, ItemBounds>? =
        actions
            .asSequence()
            .mapIndexedNotNull { index, action ->
                val id = action.id ?: return@mapIndexedNotNull null
                if (id == draggedId) return@mapIndexedNotNull null
                val bounds = itemBounds[id] ?: return@mapIndexedNotNull null
                Triple(index, id, bounds)
            }.firstOrNull { (index, _, bounds) ->
                val itemCenter = (bounds.left + bounds.right) / 2f
                (index > draggedIndex && draggedCenter > itemCenter) ||
                    (index < draggedIndex && draggedCenter < itemCenter)
            }?.let { (index, _, bounds) -> index to bounds }

    internal data class ItemBounds(
        val left: Int,
        val right: Int,
    )
}
