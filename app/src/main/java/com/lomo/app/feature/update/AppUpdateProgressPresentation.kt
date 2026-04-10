package com.lomo.app.feature.update

import com.lomo.domain.model.AppUpdateInstallState

internal data class UpdateProgressPresentation(
    val title: UpdateProgressTitle,
    val tone: UpdateProgressTone,
    val progressPercent: Int?,
    val showsDeterminateProgress: Boolean,
    val supportingMessage: UpdateProgressSupportingMessage,
)

internal enum class UpdateProgressTitle {
    Preparing,
    Downloading,
    Installing,
    Completed,
    RequiresInstallPermission,
    Failed,
}

internal enum class UpdateProgressTone {
    Neutral,
    Progress,
    Success,
    Error,
}

internal enum class UpdateProgressMessageKey {
    Preparing,
    Downloading,
    Installing,
    Completed,
}

internal sealed interface UpdateProgressSupportingMessage {
    data class Default(
        val key: UpdateProgressMessageKey,
    ) : UpdateProgressSupportingMessage

    data class Raw(
        val value: String,
    ) : UpdateProgressSupportingMessage
}

internal fun AppUpdateInstallState.toUpdateProgressPresentation(): UpdateProgressPresentation =
    when (this) {
        AppUpdateInstallState.Idle,
        AppUpdateInstallState.Preparing,
        -> {
            UpdateProgressPresentation(
                title = UpdateProgressTitle.Preparing,
                tone = UpdateProgressTone.Neutral,
                progressPercent = null,
                showsDeterminateProgress = false,
                supportingMessage = UpdateProgressSupportingMessage.Default(UpdateProgressMessageKey.Preparing),
            )
        }

        is AppUpdateInstallState.Downloading -> {
            UpdateProgressPresentation(
                title = UpdateProgressTitle.Downloading,
                tone = UpdateProgressTone.Progress,
                progressPercent = progress.coerceIn(MIN_PROGRESS_PERCENT, MAX_PROGRESS_PERCENT),
                showsDeterminateProgress = true,
                supportingMessage = UpdateProgressSupportingMessage.Default(UpdateProgressMessageKey.Downloading),
            )
        }

        AppUpdateInstallState.Installing -> {
            UpdateProgressPresentation(
                title = UpdateProgressTitle.Installing,
                tone = UpdateProgressTone.Progress,
                progressPercent = null,
                showsDeterminateProgress = false,
                supportingMessage = UpdateProgressSupportingMessage.Default(UpdateProgressMessageKey.Installing),
            )
        }

        AppUpdateInstallState.Completed -> {
            UpdateProgressPresentation(
                title = UpdateProgressTitle.Completed,
                tone = UpdateProgressTone.Success,
                progressPercent = null,
                showsDeterminateProgress = false,
                supportingMessage = UpdateProgressSupportingMessage.Default(UpdateProgressMessageKey.Completed),
            )
        }

        is AppUpdateInstallState.RequiresInstallPermission -> {
            UpdateProgressPresentation(
                title = UpdateProgressTitle.RequiresInstallPermission,
                tone = UpdateProgressTone.Progress,
                progressPercent = null,
                showsDeterminateProgress = false,
                supportingMessage = UpdateProgressSupportingMessage.Raw(message),
            )
        }

        is AppUpdateInstallState.Failed -> {
            UpdateProgressPresentation(
                title = UpdateProgressTitle.Failed,
                tone = UpdateProgressTone.Error,
                progressPercent = null,
                showsDeterminateProgress = false,
                supportingMessage = UpdateProgressSupportingMessage.Raw(message),
            )
        }
    }

private const val MIN_PROGRESS_PERCENT = 0
private const val MAX_PROGRESS_PERCENT = 100
