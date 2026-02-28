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
    val reference: MemoMenuReference = MemoMenuReference.None,
) {
    constructor(
        wordCount: Int = 0,
        createdTime: String = "",
        content: String = "",
        memo: Any?,
    ) : this(
        wordCount = wordCount,
        createdTime = createdTime,
        content = content,
        reference =
            if (memo != null) {
                MemoMenuReference.Payload(memo)
            } else {
                MemoMenuReference.None
            },
    )

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

    inline fun <reified T : Any> memoAs(): T? = (reference as? MemoMenuReference.Payload<*>)?.value as? T

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
                reference = MemoMenuReference.Id(memoId),
            )

        fun <T : Any> withPayload(
            payload: T,
            wordCount: Int = 0,
            createdTime: String = "",
            content: String = "",
            memoId: MemoMenuItemId? = null,
        ): MemoMenuState =
            MemoMenuState(
                wordCount = wordCount,
                createdTime = createdTime,
                content = content,
                reference = MemoMenuReference.Payload(payload, memoId),
            )
    }
}

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
        // Copy - actually works
        DropdownMenuItem(
            text = { Text(copyLabel) },
            onClick = {
                haptic.medium()
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as
                        ClipboardManager
                val clip = ClipData.newPlainText(clipboardMemoLabel, state.content)
                clipboard.setPrimaryClip(clip)
                onDismiss()
            },
            leadingIcon = { Icon(Icons.Outlined.FileCopy, contentDescription = copyLabel) },
        )

        // Edit
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

        // Delete
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

        // Info
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
}
