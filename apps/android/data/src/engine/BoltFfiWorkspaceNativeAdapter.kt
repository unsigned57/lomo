package com.lomo.data.engine

import com.lomo.nativebridge.WorkspaceDocumentCommandKind
import com.lomo.nativebridge.WorkspaceDocumentHistoryWrite
import com.lomo.nativebridge.WorkspaceReminderReference

internal fun WorkspaceNativeCommandSpec.toBridge(): WorkspaceDocumentCommandKind = when (this) {
    is WorkspaceNativeCommandSpec.Create ->
        WorkspaceDocumentCommandKind.Create(
            timePart = timePart,
            content = content,
        )

    is WorkspaceNativeCommandSpec.Append ->
        WorkspaceDocumentCommandKind.Append(
            timePart = timePart,
            content = content,
        )

    is WorkspaceNativeCommandSpec.Replace ->
        WorkspaceDocumentCommandKind.Replace(
            identity = identity,
            content = content,
        )

    is WorkspaceNativeCommandSpec.Remove ->
        WorkspaceDocumentCommandKind.Remove(identity = identity)

    is WorkspaceNativeCommandSpec.ToggleTask ->
        WorkspaceDocumentCommandKind.ToggleTask(
            identity = identity,
            bodyStart = bodyStart,
            bodyEnd = bodyEnd,
        )
}

internal fun WorkspaceNativeHistoryWrite.toBridge(): WorkspaceDocumentHistoryWrite =
    WorkspaceDocumentHistoryWrite(revision = revision, createdAtMs = createdAtMs)

internal fun WorkspaceNativeCommandSpec.historyWrite(): WorkspaceNativeHistoryWrite? = when (this) {
    is WorkspaceNativeCommandSpec.Create -> history

    is WorkspaceNativeCommandSpec.Append -> history

    is WorkspaceNativeCommandSpec.Replace -> history

    is WorkspaceNativeCommandSpec.Remove,
    is WorkspaceNativeCommandSpec.ToggleTask,
    -> null
}

internal fun WorkspaceNativeExpectedState.toBridge(): com.lomo.nativebridge.WorkspaceDocumentExpectedState =
    when (this) {
        WorkspaceNativeExpectedState.Absent ->
            com.lomo.nativebridge.WorkspaceDocumentExpectedState.Absent

        is WorkspaceNativeExpectedState.Match ->
            com.lomo.nativebridge.WorkspaceDocumentExpectedState.Match(fingerprint)
    }

internal fun WorkspaceReminderReference.toSnapshot(): WorkspaceReminderReferenceSnapshot =
    WorkspaceReminderReferenceSnapshot(
        opaqueId = opaqueId,
        revision = revision,
        memoIdentity = memoIdentity,
        sourceStart = sourceStart,
        sourceEnd = sourceEnd,
        tokenFingerprint = tokenFingerprint,
        fingerprintOrdinal = fingerprintOrdinal,
        embeddedId = embeddedId,
        token = token,
        dueAtLocal = dueAtLocal,
        repeatCount = repeatCount,
        firedCount = firedCount,
        done = done,
        intervalMinutes = intervalMinutes,
        recurrence = recurrence,
    )
