package com.lomo.app

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

internal sealed interface PendingLaunchAction {
    data class SharedText(
        val text: String,
    ) : PendingLaunchAction

    data class SharedImage(
        val uri: Uri,
    ) : PendingLaunchAction

    data object CreateMemo : PendingLaunchAction

    data class OpenMemo(
        val memoId: String,
    ) : PendingLaunchAction
}

internal data class PendingLaunchCommand(
    val id: Long,
    val action: PendingLaunchAction,
)

internal fun extractPendingLaunchActions(intent: Intent?): List<PendingLaunchAction> {
    if (intent == null) {
        return emptyList()
    }
    return when (intent.action) {
        Intent.ACTION_SEND,
        Intent.ACTION_SEND_MULTIPLE,
        -> extractShareLaunchActions(intent)

        MainActivity.ACTION_NEW_MEMO -> listOf(PendingLaunchAction.CreateMemo)

        MainActivity.ACTION_OPEN_MEMO ->
            intent.getStringExtra(MainActivity.EXTRA_MEMO_ID)
                ?.takeIf(String::isNotBlank)
                ?.let { memoId -> listOf(PendingLaunchAction.OpenMemo(memoId)) }
                .orEmpty()

        else -> emptyList()
    }
}

private fun extractShareLaunchActions(intent: Intent): List<PendingLaunchAction> {
    val type = intent.type.orEmpty()
    if (type.startsWith("text/")) {
        return extractSharedTexts(intent).map(PendingLaunchAction::SharedText)
    }
    if (type.startsWith("image/")) {
        return extractSharedImageUris(intent).map(PendingLaunchAction::SharedImage)
    }
    return buildList {
        extractSharedTexts(intent).forEach { text -> add(PendingLaunchAction.SharedText(text)) }
        extractSharedImageUris(intent).forEach { uri -> add(PendingLaunchAction.SharedImage(uri)) }
    }
}

private fun extractSharedTexts(intent: Intent): List<String> {
    val texts = mutableListOf<String>()
    val extras = intent.extras
    intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
        if (text.isNotBlank()) {
            texts += text
        }
    }
    extras?.getStringArrayList(Intent.EXTRA_TEXT)?.forEach { text ->
        if (text.isNotBlank()) {
            texts += text
        }
    }
    extras?.getCharSequenceArrayList(Intent.EXTRA_TEXT)?.forEach { text ->
        val normalized = text?.toString()
        if (!normalized.isNullOrBlank()) {
            texts += normalized
        }
    }
    return texts.distinct()
}

private fun extractSharedImageUris(intent: Intent): List<Uri> {
    val uris = mutableListOf<Uri>()
    IntentCompat
        .getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        ?.let(uris::add)
    IntentCompat
        .getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        ?.forEach(uris::add)
    intent.clipData?.let { clipData ->
        repeat(clipData.itemCount) { index ->
            clipData.getItemAt(index).uri?.let(uris::add)
        }
    }
    return uris.distinct()
}
