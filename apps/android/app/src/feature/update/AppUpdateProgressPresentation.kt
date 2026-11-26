package com.lomo.app.feature.update

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lomo.domain.model.AppUpdateInstallState

internal data class UpdateProgressHazeSpec(
    val scrimBlurRadius: Dp,
    val scrimTintColor: Color,
    val scrimFallbackTintColor: Color,
    val cardSurfaceColor: Color,
    val cardGlassTintColor: Color,
    val cardFallbackBorderColor: Color,
)

internal fun resolveUpdateProgressHazeSpec(colorScheme: ColorScheme): UpdateProgressHazeSpec = UpdateProgressHazeSpec(
    scrimBlurRadius = UPDATE_PROGRESS_SCRIM_BLUR_RADIUS,
    scrimTintColor = colorScheme.scrim.copy(alpha = UPDATE_PROGRESS_SCRIM_HAZE_ALPHA),
    scrimFallbackTintColor = colorScheme.scrim.copy(alpha = UPDATE_PROGRESS_SCRIM_FALLBACK_ALPHA),
    cardSurfaceColor = colorScheme.surfaceContainer,
    cardGlassTintColor = colorScheme.surfaceContainer.copy(alpha = UPDATE_PROGRESS_GLASS_TINT_ALPHA),
    cardFallbackBorderColor = colorScheme.outlineVariant.copy(alpha = UPDATE_PROGRESS_BORDER_ALPHA),
)

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
    data class Default(val key: UpdateProgressMessageKey) : UpdateProgressSupportingMessage

    data class Raw(val value: String) : UpdateProgressSupportingMessage
}

internal fun AppUpdateInstallState.toUpdateProgressPresentation(): UpdateProgressPresentation = when (this) {
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

    AppUpdateInstallState.WaitingForInstallerResult -> {
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
private val UPDATE_PROGRESS_SCRIM_BLUR_RADIUS = 20.dp
private const val UPDATE_PROGRESS_SCRIM_HAZE_ALPHA = 0.34f
private const val UPDATE_PROGRESS_SCRIM_FALLBACK_ALPHA = 0.58f
private const val UPDATE_PROGRESS_GLASS_TINT_ALPHA = 0.68f
private const val UPDATE_PROGRESS_BORDER_ALPHA = 0.88f
