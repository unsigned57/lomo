package com.lomo.app.feature.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.feature.main.MainMemoFilterSheet

/**
 * Drop-in host that wires a [MemoListFilterController] to the shared filter sheet UI.
 * Screens render `MemoFilterSheetHost(visible, controller, onDismiss)` and get the full
 * filter panel without owning any mutator callbacks themselves.
 */
@Composable
fun MemoFilterSheetHost(
    visible: Boolean,
    controller: MemoListFilterController,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val filter by controller.filter.collectAsStateWithLifecycle()
    MainMemoFilterSheet(
        filter = filter,
        onSortOptionSelected = controller.onSortOptionSelected,
        onStartDateSelected = controller.onStartDateSelected,
        onEndDateSelected = controller.onEndDateSelected,
        onHasTodoChanged = controller.onHasTodoChanged,
        onHasAttachmentChanged = controller.onHasAttachmentChanged,
        onHasUrlChanged = controller.onHasUrlChanged,
        onDismiss = onDismiss,
    )
}
