package com.lomo.app.feature.share

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.lomo.app.R
import com.lomo.app.feature.lanshare.LanSharePairingCodePolicy
import com.lomo.app.feature.lanshare.LanSharePairingDialogTriggerPolicy
import com.lomo.domain.model.ShareTransferState
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
    onBackClick: () -> Unit,
    viewModel: ShareViewModel = hiltViewModel(),
) {
    val uiState = viewModel.collectShareScreenUiState()
    val localState = rememberShareScreenLocalState()

    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val haptic = LocalAppHapticFeedback.current

    val canSaveDeviceName = localState.canSaveDeviceName(uiState.deviceName)

    LaunchedEffect(uiState.deviceName) {
        if (!(localState.isDeviceNameFieldFocused && canSaveDeviceName)) {
            localState.deviceNameInput = uiState.deviceName
        }
    }

    LaunchedEffect(Unit) {
        localState.showSettingsSection = true
        delay(80)
        localState.showPreviewSection = true
        delay(100)
        localState.showDevicesSection = true
    }

    LaunchedEffect(uiState.pairingRequiredEvent) {
        if (LanSharePairingDialogTriggerPolicy.shouldShowOnPairingRequiredEvent(uiState.pairingRequiredEvent)) {
            localState.pairingCodeInput = uiState.savedPairingCode
            localState.pairingCodeVisible = false
            viewModel.clearPairingCodeError()
            localState.showPairingDialog = true
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
                    .pointerInput(localState.isDeviceNameFieldFocused, canSaveDeviceName) {
                        detectTapGestures {
                            if (localState.isDeviceNameFieldFocused && !canSaveDeviceName) {
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                            }
                        }
                    },
        ) {
            AnimatedVisibility(
                visible = localState.showSettingsSection,
                enter = fadeIn(tween(420)) + slideInVertically(initialOffsetY = { -it / 3 }, animationSpec = tween(420)),
                exit = fadeOut(tween(220)) + shrinkVertically(animationSpec = tween(220)),
            ) {
                LanShareSettingsCard(
                    e2eEnabled = uiState.e2eEnabled,
                    pairingConfigured = uiState.pairingConfigured,
                    deviceNameInput = localState.deviceNameInput,
                    saveNameEnabled = canSaveDeviceName,
                    onToggleE2e = {
                        viewModel.updateLanShareE2eEnabled(it)
                        if (
                            LanSharePairingDialogTriggerPolicy.shouldShowOnE2eEnabled(
                                enabled = it,
                                pairingConfigured = uiState.pairingConfigured,
                            )
                        ) {
                            localState.pairingCodeInput = uiState.savedPairingCode
                            localState.pairingCodeVisible = false
                            viewModel.clearPairingCodeError()
                            localState.showPairingDialog = true
                        }
                    },
                    onOpenPairingDialog = {
                        localState.pairingCodeInput = uiState.savedPairingCode
                        localState.pairingCodeVisible = false
                        viewModel.clearPairingCodeError()
                        localState.showPairingDialog = true
                    },
                    onDeviceNameInputChange = { localState.deviceNameInput = it },
                    onNameFieldFocusChanged = { isFocused ->
                        localState.isDeviceNameFieldFocused = isFocused
                    },
                    onSaveDeviceName = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        if (canSaveDeviceName) {
                            viewModel.updateLanShareDeviceName(localState.deviceNameInput)
                        }
                    },
                    onUseSystemDeviceName = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        localState.deviceNameInput = ""
                        viewModel.updateLanShareDeviceName("")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.MediumSmall))

            AnimatedVisibility(
                visible = localState.showPreviewSection,
                enter = fadeIn(tween(420)) + slideInVertically(initialOffsetY = { it / 3 }, animationSpec = tween(420)),
                exit = fadeOut(tween(220)) + shrinkVertically(animationSpec = tween(220)),
            ) {
                MemoPreviewCard(
                    content = uiState.memoContent,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.Medium))

            AnimatedVisibility(
                visible = uiState.transferState !is ShareTransferState.Idle,
                enter = fadeIn(tween(220)) + expandVertically(animationSpec = tween(220)),
                exit = fadeOut(tween(180)) + shrinkVertically(animationSpec = tween(180)),
            ) {
                TransferStateBanner(
                    state = uiState.transferState,
                    isTechnicalMessage = viewModel::isTechnicalShareError,
                    onDismiss = { viewModel.resetTransferState() },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = AppSpacing.MediumSmall),
                )
            }

            DeviceDiscoverySection(
                showDevicesSection = localState.showDevicesSection,
                devices = uiState.discoveredDevices,
                transferState = uiState.transferState,
                onDeviceClick = { device ->
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    viewModel.sendMemo(device)
                },
                modifier = Modifier.weight(1f),
            )
        }
    }

    ShareDialogHost(
        visible = localState.showPairingDialog,
        pairingCodeInput = localState.pairingCodeInput,
        pairingCodeVisible = localState.pairingCodeVisible,
        pairingCodeError = uiState.pairingCodeError,
        pairingConfigured = uiState.pairingConfigured,
        isTechnicalMessage = viewModel::isTechnicalShareError,
        onDismiss = {
            viewModel.clearPairingCodeError()
            localState.showPairingDialog = false
        },
        onPairingCodeInputChange = {
            localState.pairingCodeInput = it
            if (uiState.pairingCodeError != null) {
                viewModel.clearPairingCodeError()
            }
        },
        onToggleVisibility = { localState.pairingCodeVisible = !localState.pairingCodeVisible },
        onClearPairingCode = {
            viewModel.clearLanSharePairingCode()
            localState.showPairingDialog = false
        },
        onSave = {
            viewModel.updateLanSharePairingCode(localState.pairingCodeInput)
            if (LanSharePairingCodePolicy.shouldDismissDialogAfterSave(localState.pairingCodeInput)) {
                localState.showPairingDialog = false
            }
        },
    )
}
