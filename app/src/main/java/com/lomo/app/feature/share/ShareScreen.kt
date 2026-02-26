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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.ShareTransferState
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
    onBackClick: () -> Unit,
    viewModel: ShareViewModel = hiltViewModel(),
) {
    val devices by viewModel.discoveredDevices.collectAsStateWithLifecycle()
    val transferState by viewModel.transferState.collectAsStateWithLifecycle()
    val e2eEnabled by viewModel.lanShareE2eEnabled.collectAsStateWithLifecycle()
    val pairingConfigured by viewModel.lanSharePairingConfigured.collectAsStateWithLifecycle()
    val pairingCodeSaved by viewModel.lanSharePairingCode.collectAsStateWithLifecycle()
    val pairingCodeError by viewModel.pairingCodeError.collectAsStateWithLifecycle()
    val pairingRequiredEvent by viewModel.pairingRequiredEvent.collectAsStateWithLifecycle()
    val deviceName by viewModel.lanShareDeviceName.collectAsStateWithLifecycle()

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val haptic = LocalAppHapticFeedback.current

    var showPairingDialog by remember { mutableStateOf(false) }
    var pairingCodeInput by remember { mutableStateOf("") }
    var pairingCodeVisible by remember { mutableStateOf(false) }
    var deviceNameInput by remember(deviceName) { mutableStateOf(deviceName) }

    var showSettingsSection by remember { mutableStateOf(false) }
    var showPreviewSection by remember { mutableStateOf(false) }
    var showDevicesSection by remember { mutableStateOf(false) }
    var isDeviceNameFieldFocused by remember { mutableStateOf(false) }

    val canSaveDeviceName = deviceNameInput.trim() != deviceName.trim()

    LaunchedEffect(Unit) {
        showSettingsSection = true
        delay(80)
        showPreviewSection = true
        delay(100)
        showDevicesSection = true
    }
    LaunchedEffect(pairingRequiredEvent) {
        if (pairingRequiredEvent > 0) {
            pairingCodeInput = pairingCodeSaved
            pairingCodeVisible = false
            viewModel.clearPairingCodeError()
            showPairingDialog = true
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.share_lan_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            haptic.medium()
                            onBackClick()
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = AppSpacing.ScreenHorizontalPadding)
                    .pointerInput(isDeviceNameFieldFocused, canSaveDeviceName) {
                        detectTapGestures {
                            if (isDeviceNameFieldFocused && !canSaveDeviceName) {
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                            }
                        }
                    },
        ) {
            AnimatedVisibility(
                visible = showSettingsSection,
                enter = fadeIn(tween(420)) + slideInVertically(initialOffsetY = { -it / 3 }, animationSpec = tween(420)),
                exit = fadeOut(tween(220)) + shrinkVertically(animationSpec = tween(220)),
            ) {
                LanShareSettingsCard(
                    e2eEnabled = e2eEnabled,
                    pairingConfigured = pairingConfigured,
                    deviceNameInput = deviceNameInput,
                    saveNameEnabled = canSaveDeviceName,
                    onToggleE2e = {
                        viewModel.updateLanShareE2eEnabled(it)
                        if (it && !pairingConfigured) {
                            pairingCodeInput = pairingCodeSaved
                            pairingCodeVisible = false
                            viewModel.clearPairingCodeError()
                            showPairingDialog = true
                        }
                    },
                    onOpenPairingDialog = {
                        pairingCodeInput = pairingCodeSaved
                        pairingCodeVisible = false
                        viewModel.clearPairingCodeError()
                        showPairingDialog = true
                    },
                    onDeviceNameInputChange = { deviceNameInput = it },
                    onNameFieldFocusChanged = { isFocused ->
                        isDeviceNameFieldFocused = isFocused
                    },
                    onSaveDeviceName = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        if (canSaveDeviceName) {
                            viewModel.updateLanShareDeviceName(deviceNameInput)
                        }
                    },
                    onUseSystemDeviceName = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        deviceNameInput = ""
                        viewModel.updateLanShareDeviceName("")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.MediumSmall))

            AnimatedVisibility(
                visible = showPreviewSection,
                enter = fadeIn(tween(420)) + slideInVertically(initialOffsetY = { it / 3 }, animationSpec = tween(420)),
                exit = fadeOut(tween(220)) + shrinkVertically(animationSpec = tween(220)),
            ) {
                MemoPreviewCard(
                    content = viewModel.memoContent,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.Medium))

            AnimatedVisibility(
                visible = transferState !is ShareTransferState.Idle,
                enter = fadeIn(tween(220)) + expandVertically(animationSpec = tween(220)),
                exit = fadeOut(tween(180)) + shrinkVertically(animationSpec = tween(180)),
            ) {
                TransferStateBanner(
                    state = transferState,
                    onDismiss = { viewModel.resetTransferState() },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = AppSpacing.MediumSmall),
                )
            }

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
                modifier = Modifier.weight(1f),
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
                                onClick = {
                                    focusManager.clearFocus(force = true)
                                    keyboardController?.hide()
                                    viewModel.sendMemo(device)
                                },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPairingDialog) {
        AlertDialog(
            onDismissRequest = {
                viewModel.clearPairingCodeError()
                showPairingDialog = false
            },
            title = {
                Text(stringResource(R.string.share_e2e_password_dialog_title))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                    Text(
                        text = stringResource(R.string.share_e2e_password_dialog_message),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = pairingCodeInput,
                        onValueChange = {
                            pairingCodeInput = it
                            if (pairingCodeError != null) {
                                viewModel.clearPairingCodeError()
                            }
                        },
                        label = { Text(stringResource(R.string.share_e2e_password_hint)) },
                        singleLine = true,
                        visualTransformation =
                            if (pairingCodeVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                        trailingIcon = {
                            TextButton(
                                onClick = { pairingCodeVisible = !pairingCodeVisible },
                            ) {
                                Text(
                                    if (pairingCodeVisible) {
                                        stringResource(R.string.share_password_hide)
                                    } else {
                                        stringResource(R.string.share_password_show)
                                    },
                                )
                            }
                        },
                        isError = pairingCodeError != null,
                        supportingText =
                            pairingCodeError?.let {
                                {
                                    Text(localizeShareErrorMessage(it))
                                }
                            },
                    )
                    if (pairingConfigured) {
                        TextButton(
                            onClick = {
                                viewModel.clearLanSharePairingCode()
                                showPairingDialog = false
                            },
                        ) {
                            Text(stringResource(R.string.action_clear_pairing_code))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.updateLanSharePairingCode(pairingCodeInput)
                        if (pairingCodeInput.trim().length in 6..64) {
                            showPairingDialog = false
                        }
                    },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.clearPairingCodeError()
                        showPairingDialog = false
                    },
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun LanShareSettingsCard(
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
private fun localizeShareErrorMessage(raw: String): String {
    val message = raw.trim()
    if (message.isBlank()) return stringResource(R.string.share_error_unknown)

    if (message.startsWith("Connection failed:")) {
        val detail = message.substringAfter("Connection failed:").trim()
        return stringResource(
            R.string.share_error_connection_failed,
            localizeShareErrorDetail(detail),
        )
    }
    if (message.startsWith("Transfer rejected by")) {
        val device = message.substringAfter("Transfer rejected by").trim()
        return if (device.isNotBlank()) {
            stringResource(R.string.share_error_transfer_rejected_by, device)
        } else {
            stringResource(R.string.share_error_transfer_rejected)
        }
    }
    return localizeShareErrorDetail(message)
}

@Composable
private fun localizeShareErrorDetail(detailRaw: String): String {
    val detail = detailRaw.trim()
    return when {
        detail.equals("Please set an end-to-end encryption password first", ignoreCase = true) -> {
            stringResource(R.string.share_error_set_password_first)
        }

        detail.equals("Please set a LAN share pairing code first", ignoreCase = true) -> {
            stringResource(R.string.share_error_set_password_first)
        }

        detail.contains("pairing code is not configured on receiver", ignoreCase = true) -> {
            stringResource(R.string.share_error_receiver_password_not_set)
        }

        detail.equals("Invalid attachment size", ignoreCase = true) -> {
            stringResource(R.string.share_error_invalid_attachment_size)
        }

        detail.equals("Attachment too large", ignoreCase = true) -> {
            stringResource(R.string.share_error_attachment_too_large)
        }

        detail.startsWith("Failed to resolve", ignoreCase = true) -> {
            stringResource(R.string.share_error_attachment_resolve_failed)
        }

        detail.startsWith("Unsupported attachment type", ignoreCase = true) -> {
            stringResource(R.string.share_error_unsupported_attachment_type)
        }

        detail.equals("Device unreachable", ignoreCase = true) -> {
            stringResource(R.string.share_error_device_unreachable)
        }

        detail.equals("Pairing code must be 6-64 characters", ignoreCase = true) -> {
            stringResource(R.string.share_error_invalid_pairing_code)
        }

        detail.equals("Invalid password", ignoreCase = true) -> {
            stringResource(R.string.share_error_invalid_pairing_code)
        }

        detail.equals("Transfer failed", ignoreCase = true) -> {
            stringResource(R.string.share_error_transfer_failed)
        }

        detail.equals("Unknown error", ignoreCase = true) -> {
            stringResource(R.string.share_error_unknown)
        }

        detail.contains("pairing code is not configured", ignoreCase = true) -> {
            stringResource(R.string.share_error_set_password_first)
        }

        else -> {
            detail
        }
    }
}

@Composable
private fun MemoPreviewCard(
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
private fun TransferStateBanner(
    state: ShareTransferState,
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
                    localizeShareErrorMessage(state.message),
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
