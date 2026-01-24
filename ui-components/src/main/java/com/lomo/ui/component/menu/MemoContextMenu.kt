package com.lomo.ui.component.menu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

data class MemoMenuState(
    val wordCount: Int = 0,
    val createdTime: String = "",
    val content: String = "",
    val memo: Any? = null,
)

@Composable
fun MemoContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    state: MemoMenuState,
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val context = LocalContext.current
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // Copy - actually works
        DropdownMenuItem(
            text = { Text("Copy") },
            onClick = {
                haptic.medium()
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as
                        ClipboardManager
                val clip = ClipData.newPlainText("memo", state.content)
                clipboard.setPrimaryClip(clip)
                onDismiss()
            },
            leadingIcon = { Icon(Icons.Outlined.FileCopy, contentDescription = "Copy memo") },
        )

        // Edit
        DropdownMenuItem(
            text = { Text("Edit") },
            onClick = {
                haptic.medium()
                onEdit()
                onDismiss()
            },
            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = "Edit memo") },
        )

        HorizontalDivider()

        // Delete
        DropdownMenuItem(
            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
            onClick = {
                haptic.heavy()
                onDelete()
                onDismiss()
            },
            leadingIcon = {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "Delete memo",
                    tint = MaterialTheme.colorScheme.error,
                )
            },
        )

        HorizontalDivider()

        // Info
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = "Characters: ${state.wordCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Created: ${state.createdTime}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
