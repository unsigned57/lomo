package com.lomo.app.feature.update

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.InstallMobile
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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

    androidx.compose.material3.BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.fillMaxWidth(),
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(UPDATE_PROGRESS_SHAPE),
                shape = UPDATE_PROGRESS_SHAPE,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                color = Color.Transparent,
            ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .clip(UPDATE_PROGRESS_SHAPE),
            ) {
                UpdateProgressBackdrop(modifier = Modifier.fillMaxSize())
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    UpdateProgressMainVisual(state.installState)
                    Spacer(modifier = Modifier.height(28.dp))
                    Text(
                        text = progressMessage(state.installState),
                        style = MaterialTheme.typography.bodyMedium,
                        color = progressSubtitleColor(),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    UpdateProgressActions(
                        state = state,
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
private fun UpdateProgressMainVisual(installState: AppUpdateInstallState) {
    when (installState) {
        AppUpdateInstallState.Idle,
        AppUpdateInstallState.Preparing,
        -> {
            CircularProgressIndicator(
                modifier = Modifier.size(60.dp),
                strokeWidth = 2.dp,
                color = progressContentColor(),
            )
        }

        is AppUpdateInstallState.Downloading -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${installState.progress}%",
                    color = progressContentColor(),
                    fontSize = 60.sp,
                    lineHeight = 60.sp,
                    fontWeight = FontWeight.Light,
                )
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { installState.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = progressContentColor(),
                    trackColor = progressContentColor().copy(alpha = 0.12f),
                    gapSize = 0.dp,
                )
            }
        }

        AppUpdateInstallState.Installing -> {
            Icon(
                imageVector = Icons.Outlined.InstallMobile,
                contentDescription = null,
                tint = progressContentColor(),
                modifier = Modifier.size(72.dp),
            )
        }

        AppUpdateInstallState.Completed -> {
            Icon(
                imageVector = Icons.Outlined.CheckCircleOutline,
                contentDescription = null,
                tint = progressContentColor(),
                modifier = Modifier.size(72.dp),
            )
        }

        is AppUpdateInstallState.RequiresInstallPermission -> {
            Icon(
                imageVector = Icons.Outlined.InstallMobile,
                contentDescription = null,
                tint = progressContentColor(),
                modifier = Modifier.size(72.dp),
            )
        }

        is AppUpdateInstallState.Failed -> {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = progressErrorColor(),
                modifier = Modifier.size(72.dp),
            )
        }
    }
}

@Composable
private fun UpdateProgressActions(
    state: AppUpdateProgressDialogState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpenInstallPermissionSettings: () -> Unit,
    onOpenReleasePage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    when (state.installState) {
        AppUpdateInstallState.Idle,
        AppUpdateInstallState.Preparing,
        is AppUpdateInstallState.Downloading,
        -> {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel), color = progressSubtitleColor())
            }
        }

        AppUpdateInstallState.Installing,
        AppUpdateInstallState.Completed,
        -> {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close), color = progressSubtitleColor())
            }
        }

        is AppUpdateInstallState.RequiresInstallPermission -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_close), color = progressSubtitleColor())
                }
                TextButton(onClick = onOpenInstallPermissionSettings) {
                    Text(stringResource(R.string.action_go_to_settings), color = progressContentColor())
                }
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.action_retry), color = progressContentColor())
                }
            }
        }

        is AppUpdateInstallState.Failed -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_close), color = progressSubtitleColor())
                }
                TextButton(onClick = { onOpenReleasePage(state.update.url) }) {
                    Text(stringResource(R.string.action_github_download), color = progressContentColor())
                }
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.action_retry), color = progressContentColor())
                }
            }
        }
    }
}

@Composable
private fun progressMessage(installState: AppUpdateInstallState): String =
    when (installState) {
        AppUpdateInstallState.Idle,
        AppUpdateInstallState.Preparing,
        -> stringResource(R.string.update_progress_preparing)

        is AppUpdateInstallState.Downloading -> stringResource(R.string.update_progress_downloading)

        AppUpdateInstallState.Installing -> stringResource(R.string.update_progress_installing)

        AppUpdateInstallState.Completed -> stringResource(R.string.update_progress_completed)

        is AppUpdateInstallState.RequiresInstallPermission -> installState.message

        is AppUpdateInstallState.Failed -> installState.message
    }

@Composable
private fun progressContentColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < DARK_THEME_LUMINANCE_THRESHOLD) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onSurface
    }

@Composable
private fun progressSubtitleColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < DARK_THEME_LUMINANCE_THRESHOLD) {
        Color.White.copy(alpha = PROGRESS_SUBTITLE_ALPHA)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

@Composable
private fun progressErrorColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < DARK_THEME_LUMINANCE_THRESHOLD) {
        Color.White
    } else {
        MaterialTheme.colorScheme.error
    }

private val UPDATE_PROGRESS_SHAPE = RoundedCornerShape(32.dp)
private const val DARK_THEME_LUMINANCE_THRESHOLD = 0.5f
private const val PROGRESS_SUBTITLE_ALPHA = 0.72f
