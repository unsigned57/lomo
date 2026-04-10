package com.lomo.app.feature.update

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.lomo.app.R
import com.lomo.domain.model.AppUpdateInstallState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LomoAppUpdateProgressDialog(
    state: AppUpdateProgressDialogState?,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpenInstallPermissionSettings: () -> Unit,
    onOpenReleasePage: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    state ?: return
    val presentation = state.installState.toUpdateProgressPresentation()

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = UPDATE_PROGRESS_SCRIM_ALPHA))
                    .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = UPDATE_PROGRESS_MAX_WIDTH)
                        .clip(UPDATE_PROGRESS_SHAPE),
                shape = UPDATE_PROGRESS_SHAPE,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 10.dp,
                shadowElevation = 18.dp,
                border =
                    BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = UPDATE_PROGRESS_BORDER_ALPHA),
                    ),
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    UpdateProgressBackdrop(modifier = Modifier.matchParentSize())
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Text(
                            text = presentation.title.asText(),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = progressAccentColor(presentation.tone),
                            textAlign = TextAlign.Center,
                        )

                        UpdateProgressMainVisual(
                            installState = state.installState,
                            presentation = presentation,
                        )

                        Text(
                            text = supportingMessageAsText(presentation.supportingMessage),
                            style = MaterialTheme.typography.bodyMedium,
                            color = progressSupportingColor(presentation.tone),
                            textAlign = TextAlign.Center,
                        )

                        UpdateProgressActions(
                            state = state,
                            presentation = presentation,
                            onCancel = onCancel,
                            onRetry = onRetry,
                            onOpenInstallPermissionSettings = onOpenInstallPermissionSettings,
                            onOpenReleasePage = onOpenReleasePage,
                            onDismiss = onDismiss,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateProgressMainVisual(
    installState: AppUpdateInstallState,
    presentation: UpdateProgressPresentation,
) {
    when (installState) {
        AppUpdateInstallState.Idle,
        AppUpdateInstallState.Preparing,
        -> {
            CircularProgressIndicator(
                modifier = Modifier.size(56.dp),
                strokeWidth = 4.dp,
                color = progressAccentColor(presentation.tone),
                trackColor = progressTrackColor(),
            )
        }

        is AppUpdateInstallState.Downloading -> {
            val progress = checkNotNull(presentation.progressPercent)
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "$progress%",
                    color = progressAccentColor(presentation.tone),
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(PROGRESS_BAR_SHAPE),
                    color = progressAccentColor(presentation.tone),
                    trackColor = progressTrackColor(),
                    gapSize = 0.dp,
                )
            }
        }

        AppUpdateInstallState.Installing -> {
            Icon(
                imageVector = Icons.Outlined.InstallMobile,
                contentDescription = null,
                tint = progressAccentColor(presentation.tone),
                modifier = Modifier.size(72.dp),
            )
        }

        AppUpdateInstallState.Completed -> {
            Icon(
                imageVector = Icons.Outlined.CheckCircleOutline,
                contentDescription = null,
                tint = progressAccentColor(presentation.tone),
                modifier = Modifier.size(72.dp),
            )
        }

        is AppUpdateInstallState.RequiresInstallPermission -> {
            Icon(
                imageVector = Icons.Outlined.InstallMobile,
                contentDescription = null,
                tint = progressAccentColor(presentation.tone),
                modifier = Modifier.size(72.dp),
            )
        }

        is AppUpdateInstallState.Failed -> {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = progressAccentColor(presentation.tone),
                modifier = Modifier.size(72.dp),
            )
        }
    }
}

@Composable
private fun UpdateProgressActions(
    state: AppUpdateProgressDialogState,
    presentation: UpdateProgressPresentation,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpenInstallPermissionSettings: () -> Unit,
    onOpenReleasePage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state.installState) {
            AppUpdateInstallState.Idle,
            AppUpdateInstallState.Preparing,
            is AppUpdateInstallState.Downloading,
            -> {
                UpdateProgressSecondaryAction(
                    text = stringResource(R.string.action_cancel),
                    onClick = onCancel,
                )
            }

            AppUpdateInstallState.Installing,
            AppUpdateInstallState.Completed,
            -> {
                UpdateProgressSecondaryAction(
                    text = stringResource(R.string.action_close),
                    onClick = onDismiss,
                )
            }

            is AppUpdateInstallState.RequiresInstallPermission -> {
                UpdateProgressSecondaryAction(
                    text = stringResource(R.string.action_close),
                    onClick = onDismiss,
                )
                UpdateProgressPrimaryAction(
                    text = stringResource(R.string.action_go_to_settings),
                    tone = presentation.tone,
                    onClick = onOpenInstallPermissionSettings,
                )
                UpdateProgressPrimaryAction(
                    text = stringResource(R.string.action_retry),
                    tone = presentation.tone,
                    onClick = onRetry,
                )
            }

            is AppUpdateInstallState.Failed -> {
                UpdateProgressSecondaryAction(
                    text = stringResource(R.string.action_close),
                    onClick = onDismiss,
                )
                UpdateProgressPrimaryAction(
                    text = stringResource(R.string.action_github_download),
                    tone = presentation.tone,
                    onClick = { onOpenReleasePage(state.update.url) },
                )
                UpdateProgressPrimaryAction(
                    text = stringResource(R.string.action_retry),
                    tone = presentation.tone,
                    onClick = onRetry,
                )
            }
        }
    }
}

@Composable
private fun UpdateProgressPrimaryAction(
    text: String,
    tone: UpdateProgressTone,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = progressAccentColor(tone)),
    ) {
        Text(text = text)
    }
}

@Composable
private fun UpdateProgressSecondaryAction(
    text: String,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        colors =
            ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    ) {
        Text(text = text)
    }
}

@Composable
private fun UpdateProgressTitle.asText(): String =
    when (this) {
        UpdateProgressTitle.Preparing -> stringResource(R.string.update_progress_title_preparing)
        UpdateProgressTitle.Downloading -> stringResource(R.string.update_progress_title_downloading)
        UpdateProgressTitle.Installing -> stringResource(R.string.update_progress_title_installing)
        UpdateProgressTitle.Completed -> stringResource(R.string.update_progress_title_completed)
        UpdateProgressTitle.RequiresInstallPermission ->
            stringResource(R.string.update_progress_title_requires_install_permission)
        UpdateProgressTitle.Failed -> stringResource(R.string.update_progress_title_failed)
    }

@Composable
private fun supportingMessageAsText(message: UpdateProgressSupportingMessage): String =
    when (message) {
        is UpdateProgressSupportingMessage.Default ->
            when (message.key) {
                UpdateProgressMessageKey.Preparing -> stringResource(R.string.update_progress_preparing)
                UpdateProgressMessageKey.Downloading -> stringResource(R.string.update_progress_downloading)
                UpdateProgressMessageKey.Installing -> stringResource(R.string.update_progress_installing)
                UpdateProgressMessageKey.Completed -> stringResource(R.string.update_progress_completed)
            }
        is UpdateProgressSupportingMessage.Raw -> message.value
    }

@Composable
private fun progressAccentColor(tone: UpdateProgressTone): Color =
    when (tone) {
        UpdateProgressTone.Neutral -> MaterialTheme.colorScheme.onSurface
        UpdateProgressTone.Progress -> MaterialTheme.colorScheme.primary
        UpdateProgressTone.Success -> MaterialTheme.colorScheme.primary
        UpdateProgressTone.Error -> MaterialTheme.colorScheme.error
    }

@Composable
private fun progressSupportingColor(tone: UpdateProgressTone): Color =
    when (tone) {
        UpdateProgressTone.Error -> MaterialTheme.colorScheme.error
        UpdateProgressTone.Neutral,
        UpdateProgressTone.Progress,
        UpdateProgressTone.Success,
        -> MaterialTheme.colorScheme.onSurfaceVariant
    }

@Composable
private fun progressTrackColor(): Color = MaterialTheme.colorScheme.surfaceVariant

private val UPDATE_PROGRESS_SHAPE = RoundedCornerShape(28.dp)
private val PROGRESS_BAR_SHAPE = RoundedCornerShape(999.dp)
private val UPDATE_PROGRESS_MAX_WIDTH = 420.dp
private const val UPDATE_PROGRESS_SCRIM_ALPHA = 0.58f
private const val UPDATE_PROGRESS_BORDER_ALPHA = 0.88f
