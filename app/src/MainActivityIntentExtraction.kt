package com.lomo.app

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import com.lomo.domain.model.RecordingDeepLink

internal fun extractInitialPendingLaunchActions(
    activityInstanceState: ActivityInstanceState,
    intent: Intent?,
): List<PendingLaunchAction> =
    if (shouldProcessInitialLaunchIntent(activityInstanceState = activityInstanceState, intent = intent)) {
        extractPendingLaunchActions(intent = intent)
    } else {
        emptyList()
    }

internal fun shouldProcessInitialLaunchIntent(
    activityInstanceState: ActivityInstanceState,
    intent: Intent?,
): Boolean =
    when (activityInstanceState) {
        ActivityInstanceState.Fresh -> true
        ActivityInstanceState.Restored -> !intent.isLaunchFromHistory()
    }

internal fun extractPendingLaunchActions(intent: Intent?): List<PendingLaunchAction> {
    if (intent == null) {
        return emptyList()
    }
    return when (intent.action) {
        Intent.ACTION_SEND,
        Intent.ACTION_SEND_MULTIPLE,
        -> extractShareLaunchActions(intent)

        MainActivity.ACTION_OPEN_MEMO ->
            intent.getStringExtra(MainActivity.EXTRA_MEMO_ID)
                ?.takeIf(String::isNotBlank)
                ?.let { memoId -> listOf(PendingLaunchAction.OpenMemo(memoId)) }
                .orEmpty()

        RecordingDeepLink.ACTION_OPEN_SAVED_MEMO ->
            intent.getStringExtra(RecordingDeepLink.EXTRA_MEMO_ID)
                ?.takeIf(String::isNotBlank)
                ?.let { memoId -> listOf(PendingLaunchAction.OpenMemo(memoId)) }
                .orEmpty()

        "com.lomo.reminder.action.OPEN" ->
            intent.getStringExtra("memo_id")
                ?.takeIf(String::isNotBlank)
                ?.let { memoId -> listOf(PendingLaunchAction.OpenMemo(memoId)) }
                .orEmpty()

        else -> emptyList()
    }
}

private fun Intent?.isLaunchFromHistory(): Boolean =
    this != null && flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0

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
