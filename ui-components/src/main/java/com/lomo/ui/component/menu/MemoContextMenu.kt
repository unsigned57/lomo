package com.lomo.ui.component.menu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lomo.ui.R
import kotlin.jvm.JvmInline

@JvmInline
value class MemoMenuItemId(
    val value: String,
)

sealed interface MemoMenuReference {
    data object None : MemoMenuReference

    data class Id(
        val id: MemoMenuItemId,
    ) : MemoMenuReference

    data class Payload<T : Any>(
        val value: T,
        val id: MemoMenuItemId? = null,
    ) : MemoMenuReference
}

data class MemoMenuState(
    val wordCount: Int = 0,
    val createdTime: String = "",
    val content: String = "",
    val isPinned: Boolean = false,
    val reference: MemoMenuReference = MemoMenuReference.None,
    val imageUrls: List<String> = emptyList(),
) {
    // Legacy accessor kept for source compatibility. Prefer `memoAs<T>()` in new code.
    val memo: Any?
        get() = (reference as? MemoMenuReference.Payload<*>)?.value

    val memoId: MemoMenuItemId?
        get() =
            when (reference) {
                MemoMenuReference.None -> null
                is MemoMenuReference.Id -> reference.id
                is MemoMenuReference.Payload<*> -> reference.id
            }

    constructor(
        wordCount: Int = 0,
        createdTime: String = "",
        content: String = "",
        isPinned: Boolean = false,
        memo: Any?,
        imageUrls: List<String> = emptyList(),
    ) : this(
        wordCount = wordCount,
        createdTime = createdTime,
        content = content,
        isPinned = isPinned,
        reference =
            if (memo != null) {
                MemoMenuReference.Payload(memo)
            } else {
                MemoMenuReference.None
            },
        imageUrls = imageUrls,
    )

    companion object {
        fun withId(
            memoId: MemoMenuItemId,
            wordCount: Int = 0,
            createdTime: String = "",
            content: String = "",
            isPinned: Boolean = false,
        ): MemoMenuState =
            MemoMenuState(
                wordCount = wordCount,
                createdTime = createdTime,
                content = content,
                isPinned = isPinned,
                reference = MemoMenuReference.Id(memoId),
            )

        fun <T : Any> withPayload(
            payload: T,
            wordCount: Int = 0,
            createdTime: String = "",
            content: String = "",
            isPinned: Boolean = false,
            memoId: MemoMenuItemId? = null,
        ): MemoMenuState =
            MemoMenuState(
                wordCount = wordCount,
                createdTime = createdTime,
                content = content,
                isPinned = isPinned,
                reference = MemoMenuReference.Payload(payload, memoId),
            )
    }
}

inline fun <reified T : Any> MemoMenuState.memoAs(): T? =
    (reference as? MemoMenuReference.Payload<*>)?.value as? T

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
    val copyLabel = stringResource(R.string.action_copy)
    val editLabel = stringResource(R.string.action_edit)
    val deleteLabel = stringResource(R.string.action_delete)
    val clipboardMemoLabel = stringResource(R.string.clipboard_label_memo)

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
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

        DropdownMenuItem(
            text = { Text(editLabel) },
            onClick = {
                haptic.medium()
                onEdit()
                onDismiss()
            },
            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = editLabel) },
        )

        HorizontalDivider()

        DropdownMenuItem(
            text = { Text(deleteLabel, color = MaterialTheme.colorScheme.error) },
            onClick = {
                haptic.heavy()
                onDelete()
                onDismiss()
            },
            leadingIcon = {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = deleteLabel,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
        )

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
            text = stringResource(R.string.memo_info_characters_value, state.wordCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.memo_info_created_value, state.createdTime),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
