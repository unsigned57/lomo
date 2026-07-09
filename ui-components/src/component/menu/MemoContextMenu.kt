package com.lomo.ui.component.menu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import com.lomo.ui.generated.resources.Res
import com.lomo.ui.generated.resources.*
import kotlinx.collections.immutable.ImmutableList
import kotlin.jvm.JvmInline

@JvmInline
value class MemoMenuItemId(
    val value: String,
)

data class MemoMenuState(
    val wordCount: Int = 0,
    val createdTime: String = "",
    val content: String = "",
    val memoId: MemoMenuItemId? = null,
    val imageUrls: List<String> = emptyList(),
) {
    companion object {
        fun withId(
            memoId: MemoMenuItemId,
            wordCount: Int = 0,
            createdTime: String = "",
            content: String = "",
        ): MemoMenuState =
            MemoMenuState(
                wordCount = wordCount,
                createdTime = createdTime,
                content = content,
                memoId = memoId,
            )
    }
}

@Composable
fun MemoContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    state: MemoMenuState,
    actions: ImmutableList<ActionItemUi>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    val copyLabel = stringResource(Res.string.action_copy)
    val clipboardMemoLabel = stringResource(Res.string.clipboard_label_memo)

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier,
    ) {
        DropdownMenuItem(
            text = { Text(copyLabel) },
            onClick = {
                haptic.medium()
                copyMemoToClipboard(
                    context = context,
                    label = clipboardMemoLabel,
                    content = state.content,
                )
                onDismiss()
            },
            leadingIcon = { Icon(Icons.Outlined.FileCopy, contentDescription = copyLabel) },
        )

        actions.filter(ActionItemUi::isVisible).forEach { action ->
            DropdownMenuItem(
                text = {
                    Text(
                        text = action.label,
                        color =
                            if (action.isDestructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                    )
                },
                onClick = {
                    performActionItemHaptic(haptic, action.haptic)
                    action.onClick()
                    if (action.dismissAfterClick) {
                        onDismiss()
                    }
                },
                enabled = action.isEnabled,
                leadingIcon = {
                    Icon(
                        action.icon,
                        contentDescription = action.label,
                        tint =
                            if (action.isDestructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                },
            )
        }

        HorizontalDivider()

        MemoInfoSection(state = state)
    }
}

private fun copyMemoToClipboard(
    context: Context,
    label: String,
    content: String,
) {
    val clipboard =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, content)
    clipboard.setPrimaryClip(clip)
}

@Composable
private fun MemoInfoSection(state: MemoMenuState) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(Res.string.memo_info_characters_value, state.wordCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(Res.string.memo_info_created_value, state.createdTime),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
