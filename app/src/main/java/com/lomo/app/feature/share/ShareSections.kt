package com.lomo.app.feature.share

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.ShareTransferState
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing

@Composable
fun DeviceDiscoverySection(
    showDevicesSection: Boolean,
    devices: List<DiscoveredDevice>,
    transferState: ShareTransferState,
    onDeviceClick: (DiscoveredDevice) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = showDevicesSection,
        enter = fadeIn(tween(420)) + slideInVertically(initialOffsetY = { it / 4 }, animationSpec = tween(420)),
        exit = fadeOut(tween(220)),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.WifiFind,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(AppSpacing.Small))
                Text(
                    text = stringResource(R.string.share_nearby_devices),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.share_devices_count, devices.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (devices.isEmpty()) {
                    Spacer(modifier = Modifier.width(AppSpacing.Small))
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.MediumSmall))
        }
    }

    val listState = rememberLazyListState()
    AnimatedContent(
        targetState = devices.isEmpty(),
        transitionSpec = {
            fadeIn(tween(260)) togetherWith fadeOut(tween(220))
        },
        label = "device-list",
        modifier = modifier,
    ) { isEmpty ->
        if (isEmpty) {
            DeviceSearchingState(modifier = Modifier.fillMaxSize())
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.MediumSmall),
            ) {
                itemsIndexed(devices, key = { _, item -> "${item.host}:${item.port}" }) { _, device ->
                    DeviceCard(
                        device = device,
                        isEnabled =
                            transferState is ShareTransferState.Idle ||
                                transferState is ShareTransferState.Success ||
                                transferState is ShareTransferState.Error,
                        onClick = { onDeviceClick(device) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
fun LanShareSettingsCard(
    e2eEnabled: Boolean,
    pairingConfigured: Boolean,
    deviceNameInput: String,
    saveNameEnabled: Boolean,
    onToggleE2e: (Boolean) -> Unit,
    onOpenPairingDialog: () -> Unit,
    onDeviceNameInputChange: (String) -> Unit,
    onNameFieldFocusChanged: (Boolean) -> Unit,
    onSaveDeviceName: () -> Unit,
    onUseSystemDeviceName: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isNameFieldFocused by remember { mutableStateOf(false) }
    val showNameActions = isNameFieldFocused

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = AppShapes.Large,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .padding(AppSpacing.Medium)
                    .animateContentSize(animationSpec = tween(280)),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.MediumSmall),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                Spacer(modifier = Modifier.width(AppSpacing.MediumSmall))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.share_e2e_enabled_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.share_e2e_enabled_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusBadge(
                    text =
                        if (e2eEnabled) {
                            stringResource(R.string.share_e2e_state_enabled)
                        } else {
                            stringResource(R.string.share_e2e_state_disabled)
                        },
                    active = e2eEnabled,
                )
                Spacer(modifier = Modifier.width(AppSpacing.Small))
                Switch(
                    checked = e2eEnabled,
                    onCheckedChange = onToggleE2e,
                )
            }

            AnimatedVisibility(
                visible = e2eEnabled,
                enter = fadeIn(tween(180)) + expandVertically(animationSpec = tween(180)),
                exit = fadeOut(tween(150)) + shrinkVertically(animationSpec = tween(150)),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = AppShapes.Medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = AppSpacing.MediumSmall,
                                    vertical = AppSpacing.Small,
                                ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.share_e2e_password_title),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text =
                                    if (pairingConfigured) {
                                        stringResource(R.string.share_e2e_password_configured)
                                    } else {
                                        stringResource(R.string.share_e2e_password_not_set)
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color =
                                    if (pairingConfigured) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                            )
                        }
                        FilledTonalButton(onClick = onOpenPairingDialog) {
                            Text(stringResource(R.string.share_e2e_password_set))
                        }
                    }
                }
            }

            OutlinedTextField(
                value = deviceNameInput,
                onValueChange = onDeviceNameInputChange,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .onFocusChanged { state ->
                            isNameFieldFocused = state.isFocused
                            onNameFieldFocusChanged(state.isFocused)
                        },
                singleLine = true,
                label = { Text(stringResource(R.string.share_device_name_label)) },
                placeholder = { Text(stringResource(R.string.share_device_name_placeholder)) },
                keyboardOptions =
                    androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = ImeAction.Done,
                        keyboardType = KeyboardType.Text,
                    ),
                keyboardActions =
                    androidx.compose.foundation.text.KeyboardActions(
                        onDone = { onSaveDeviceName() },
                    ),
            )

            AnimatedVisibility(
                visible = showNameActions,
                enter = fadeIn(tween(180)) + slideInVertically(initialOffsetY = { -it / 2 }, animationSpec = tween(220)),
                exit = fadeOut(tween(150)) + slideOutVertically(targetOffsetY = { -it / 3 }, animationSpec = tween(180)),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onUseSystemDeviceName) {
                        Text(stringResource(R.string.share_device_name_use_system))
                    }
                    Spacer(modifier = Modifier.width(AppSpacing.Small))
                    Button(
                        onClick = onSaveDeviceName,
                        enabled = saveNameEnabled,
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color =
            if (active) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        shape = AppShapes.Full,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color =
                if (active) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.padding(horizontal = AppSpacing.Small, vertical = AppSpacing.ExtraSmall),
        )
    }
}

@Composable
fun MemoPreviewCard(
    content: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = AppShapes.Large,
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.Medium),
        ) {
            Text(
                text = stringResource(R.string.share_memo_preview_title),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(AppSpacing.Small))
            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun TransferStateBanner(
    state: ShareTransferState,
    isTechnicalMessage: (String) -> Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    data class BannerState(
        val containerColor: Color,
        val contentColor: Color,
        val icon: androidx.compose.ui.graphics.vector.ImageVector?,
        val text: String,
    )

    val bannerState =
        when (state) {
            is ShareTransferState.Sending -> {
                BannerState(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                    null,
                    stringResource(R.string.share_status_connecting),
                )
            }

            is ShareTransferState.WaitingApproval -> {
                BannerState(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                    null,
                    stringResource(R.string.share_status_waiting_approval, state.deviceName),
                )
            }

            is ShareTransferState.Transferring -> {
                BannerState(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                    null,
                    stringResource(R.string.share_status_transferring),
                )
            }

            is ShareTransferState.Success -> {
                BannerState(
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer,
                    Icons.Filled.CheckCircle,
                    stringResource(R.string.share_status_sent, state.deviceName),
                )
            }

            is ShareTransferState.Error -> {
                BannerState(
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer,
                    Icons.Filled.Error,
                    ShareErrorPresenter.message(state.error, isTechnicalMessage),
                )
            }

            else -> {
                return
            }
        }

    Surface(
        modifier = modifier,
        color = bannerState.containerColor,
        shape = AppShapes.Medium,
        onClick = {
            if (state is ShareTransferState.Success || state is ShareTransferState.Error) {
                onDismiss()
            }
        },
    ) {
        Column(
            modifier =
                Modifier
                    .padding(AppSpacing.Medium)
                    .animateContentSize(animationSpec = tween(180)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (bannerState.icon != null) {
                    Icon(
                        bannerState.icon,
                        contentDescription = null,
                        tint = bannerState.contentColor,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.Small))
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = bannerState.contentColor,
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.Small))
                }

                Text(
                    text = bannerState.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = bannerState.contentColor,
                )
            }

            if (state is ShareTransferState.Transferring) {
                Spacer(modifier = Modifier.height(AppSpacing.Small))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(AppShapes.ExtraSmall),
                    color = bannerState.contentColor,
                    trackColor = bannerState.contentColor.copy(alpha = 0.2f),
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: DiscoveredDevice,
    isEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        enabled = isEnabled,
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        shape = AppShapes.Medium,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Surface(
                    modifier = Modifier.size(44.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.PhoneAndroid,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Box(
                    modifier =
                        Modifier
                            .size(12.dp)
                            .align(Alignment.BottomEnd)
                            .background(
                                color = MaterialTheme.colorScheme.surface,
                                shape = CircleShape,
                            ).padding(2.dp)
                            .background(
                                color = MaterialTheme.colorScheme.tertiary,
                                shape = CircleShape,
                            ),
                )
            }

            Spacer(modifier = Modifier.width(AppSpacing.Medium))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${device.host}:${device.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Icon(
                Icons.Outlined.Wifi,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun DeviceSearchingState(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "search-pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1200, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "pulse-alpha",
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1200, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "pulse-scale",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.WifiFind,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier
                    .size(64.dp)
                    .graphicsLayer {
                        alpha = pulseAlpha
                        scaleX = pulseScale
                        scaleY = pulseScale
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
        )
        Spacer(modifier = Modifier.height(AppSpacing.Medium))
        Text(
            text = stringResource(R.string.share_searching_devices),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(AppSpacing.ExtraSmall))
        Text(
            text = stringResource(R.string.share_searching_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}
