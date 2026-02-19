package com.lomo.ui.component.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoMenuBottomSheet(
    state: MemoMenuState,
    sheetState: SheetState,
    onDismissRequest: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onLanShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        dragHandle = {
            // Custom Drag Handle to remove standard ripple/rectangular mask
            Box(
                modifier =
                    Modifier
                        .padding(vertical = 22.dp)
                        .width(32.dp)
                        .size(32.dp, 4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
            )
        },
    ) {
        MemoActionSheet(
            state = state,
            onCopy = onCopy,
            onShare = onShare,
            onLanShare = onLanShare,
            onEdit = onEdit,
            onDelete = onDelete,
            onDismiss = onDismissRequest,
        )
    }
}
