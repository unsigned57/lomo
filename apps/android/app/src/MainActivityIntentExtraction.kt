package com.lomo.app

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import com.lomo.domain.model.RecordingDeepLink
import com.lomo.domain.model.ReminderDeepLink

internal fun extractInitialPendingLaunchActions(
    activityInstanceState: ActivityInstanceState,
    intent: Intent?,
): List<PendingLaunchAction> =
    if (shouldProcessInitialLaunchIntent(activityInstanceState = activityInstanceState)) {
        extractPendingLaunchActions(intent = intent)
    } else {
        emptyList()
    }

/**
 * Only a fresh Activity start may extract its launching intent. A restored instance replays
 * exclusively from the saved-state [PendingLaunchCommandSnapshot] queue: the original intent is
 * still attached on a configuration recreate (no `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` on e.g.
 * fontScale), so re-extracting it would double-fire every already-dispatched command.
 */
internal fun shouldProcessInitialLaunchIntent(
    activityInstanceState: ActivityInstanceState,
): Boolean = activityInstanceState == ActivityInstanceState.Fresh

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

        ReminderDeepLink.ACTION_OPEN ->
            intent.getStringExtra(ReminderDeepLink.EXTRA_MEMO_ID)
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
    val clipData = intent.clipData
    if (clipData != null) {
        repeat(clipData.itemCount) { index ->
            clipData.getItemAt(index).uri?.let(uris::add)
        }
    }
    return uris.distinct()
}
