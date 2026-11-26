package com.lomo.app.feature.update

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.lomo.ui.component.common.ExpressiveLoadingIndicator
import com.lomo.ui.component.common.LocalLomoHazeState
import com.lomo.ui.component.common.LomoTextButton
import com.lomo.ui.icon.LomoIcon
import com.lomo.ui.icon.LomoIcons
import com.lomo.ui.theme.AppShapes
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.hazeGlass

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
    val hazeState = LocalLomoHazeState.current
    val hazeSpec = resolveUpdateProgressHazeSpec(MaterialTheme.colorScheme)

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        properties =
        DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        UpdateProgressScrim(
            hazeState = hazeState,
            hazeSpec = hazeSpec,
        ) {
            UpdateProgressDialogCard(
                hazeState = hazeState,
                hazeSpec = hazeSpec,
            ) {
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
                        style = MaterialTheme.typography.headlineSmallEmphasized,
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

@Composable
private fun UpdateProgressScrim(
    hazeState: HazeState?,
    hazeSpec: UpdateProgressHazeSpec,
    content: @Composable () -> Unit,
) {
    val scrimModifier =
        if (hazeState != null) {
            Modifier.hazeBlur(
                input = HazeInput.Sources(hazeState),
                style =
                HazeBlurStyle {
                    blurRadius(hazeSpec.scrimBlurRadius)
                    colorEffects(listOf(HazeColorEffect.tint(hazeSpec.scrimTintColor)))
                    fallbackColorEffect(HazeColorEffect.tint(hazeSpec.scrimFallbackTintColor))
                },
            )
        } else {
            Modifier.background(hazeSpec.scrimFallbackTintColor)
        }
    Box(
        modifier =
        Modifier
            .fillMaxSize()
            .then(scrimModifier)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun UpdateProgressDialogCard(
    hazeState: HazeState?,
    hazeSpec: UpdateProgressHazeSpec,
    content: @Composable () -> Unit,
) {
    if (hazeState != null) {
        val glassStyle =
            remember(hazeSpec.cardSurfaceColor, hazeSpec.cardGlassTintColor) {
                GlassStyle.regular.then {
                    shape(UPDATE_PROGRESS_SHAPE)
                    backgroundColor(hazeSpec.cardSurfaceColor)
                    tint(hazeSpec.cardGlassTintColor)
                }
            }
        Surface(
            modifier =
            Modifier
                .fillMaxWidth()
                .widthIn(max = UPDATE_PROGRESS_MAX_WIDTH)
                .hazeGlass(input = HazeInput.Sources(hazeState), style = glassStyle),
            shape = UPDATE_PROGRESS_SHAPE,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            content = content,
        )
    } else {
        Surface(
            modifier =
            Modifier
                .fillMaxWidth()
                .widthIn(max = UPDATE_PROGRESS_MAX_WIDTH),
            shape = UPDATE_PROGRESS_SHAPE,
            color = hazeSpec.cardSurfaceColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            border = BorderStroke(width = 1.dp, color = hazeSpec.cardFallbackBorderColor),
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UpdateProgressMainVisual(installState: AppUpdateInstallState, presentation: UpdateProgressPresentation) {
    when (installState) {
        AppUpdateInstallState.Idle,
        AppUpdateInstallState.Preparing,
        -> {
            ExpressiveLoadingIndicator(
                color = progressAccentColor(presentation.tone),
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
                LinearWavyProgressIndicator(
                    progress = { progress / 100f },
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(WAVY_PROGRESS_BAR_HEIGHT)
                        .clip(PROGRESS_BAR_SHAPE),
                    color = progressAccentColor(presentation.tone),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    gapSize = 0.dp,
                )
            }
        }

        AppUpdateInstallState.Installing,
        AppUpdateInstallState.WaitingForInstallerResult,
        is AppUpdateInstallState.RequiresInstallPermission,
        -> {
            LomoIcon(
                icon = LomoIcons.InstallMobile,
                contentDescription = null,
                tint = progressAccentColor(presentation.tone),
                modifier = Modifier.size(72.dp),
            )
        }

        AppUpdateInstallState.Completed -> {
            LomoIcon(
                icon = LomoIcons.CheckCircle,
                contentDescription = null,
                tint = progressAccentColor(presentation.tone),
                modifier = Modifier.size(72.dp),
            )
        }

        is AppUpdateInstallState.Failed -> {
            LomoIcon(
                icon = LomoIcons.Error,
                contentDescription = null,
                tint = progressAccentColor(presentation.tone),
                modifier = Modifier.size(72.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
    val secondaryColor = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryColor = progressAccentColor(presentation.tone)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (state.installState) {
            AppUpdateInstallState.Idle,
            AppUpdateInstallState.Preparing,
            is AppUpdateInstallState.Downloading,
            -> {
                UpdateProgressActionButton(
                    text = stringResource(R.string.action_cancel),
                    color = secondaryColor,
                    onClick = onCancel,
                )
            }

            AppUpdateInstallState.Installing,
            AppUpdateInstallState.WaitingForInstallerResult,
            AppUpdateInstallState.Completed,
            -> {
                UpdateProgressActionButton(
                    text = stringResource(R.string.action_close),
                    color = secondaryColor,
                    onClick = onDismiss,
                )
            }

            is AppUpdateInstallState.RequiresInstallPermission -> {
                UpdateProgressActionButton(
                    text = stringResource(R.string.action_close),
                    color = secondaryColor,
                    onClick = onDismiss,
                )
                UpdateProgressActionButton(
                    text = stringResource(R.string.action_go_to_settings),
                    color = primaryColor,
                    onClick = onOpenInstallPermissionSettings,
                )
                UpdateProgressActionButton(
                    text = stringResource(R.string.action_retry),
                    color = primaryColor,
                    onClick = onRetry,
                )
            }

            is AppUpdateInstallState.Failed -> {
                UpdateProgressActionButton(
                    text = stringResource(R.string.action_close),
                    color = secondaryColor,
                    onClick = onDismiss,
                )
                UpdateProgressActionButton(
                    text = stringResource(R.string.action_github_download),
                    color = primaryColor,
                    onClick = { onOpenReleasePage(state.update.url) },
                )
                UpdateProgressActionButton(
                    text = stringResource(R.string.action_retry),
                    color = primaryColor,
                    onClick = onRetry,
                )
            }
        }
    }
}

@Composable
private fun UpdateProgressActionButton(text: String, color: Color, onClick: () -> Unit) {
    LomoTextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = color),
    ) {
        Text(text = text)
    }
}

@Composable
private fun UpdateProgressTitle.asText(): String = when (this) {
    UpdateProgressTitle.Preparing -> stringResource(R.string.update_progress_title_preparing)

    UpdateProgressTitle.Downloading -> stringResource(R.string.update_progress_title_downloading)

    UpdateProgressTitle.Installing -> stringResource(R.string.update_progress_title_installing)

    UpdateProgressTitle.Completed -> stringResource(R.string.update_progress_title_completed)

    UpdateProgressTitle.RequiresInstallPermission ->
        stringResource(R.string.update_progress_title_requires_install_permission)

    UpdateProgressTitle.Failed -> stringResource(R.string.update_progress_title_failed)
}

@Composable
private fun supportingMessageAsText(message: UpdateProgressSupportingMessage): String = when (message) {
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
private fun progressAccentColor(tone: UpdateProgressTone): Color = when (tone) {
    UpdateProgressTone.Neutral -> MaterialTheme.colorScheme.onSurface
    UpdateProgressTone.Progress -> MaterialTheme.colorScheme.primary
    UpdateProgressTone.Success -> MaterialTheme.colorScheme.primary
    UpdateProgressTone.Error -> MaterialTheme.colorScheme.error
}

@Composable
private fun progressSupportingColor(tone: UpdateProgressTone): Color = when (tone) {
    UpdateProgressTone.Error -> MaterialTheme.colorScheme.error

    UpdateProgressTone.Neutral,
    UpdateProgressTone.Progress,
    UpdateProgressTone.Success,
    -> MaterialTheme.colorScheme.onSurfaceVariant
}

private val UPDATE_PROGRESS_SHAPE = AppShapes.ExtraLarge
private val PROGRESS_BAR_SHAPE = AppShapes.Full
private val WAVY_PROGRESS_BAR_HEIGHT = 16.dp
private val UPDATE_PROGRESS_MAX_WIDTH = 420.dp
