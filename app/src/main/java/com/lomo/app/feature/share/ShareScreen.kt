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
import com.lomo.domain.usecase.LanSharePairingCodePolicy
import com.lomo.app.feature.lanshare.LanSharePairingDialogTriggerPolicy
import com.lomo.domain.model.ShareTransferState
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.coroutines.delay

private const val SHARE_SETTINGS_STAGGER_DELAY_MILLIS = 80L
private const val SHARE_PREVIEW_STAGGER_DELAY_MILLIS = 100L
private const val SHARE_SECTION_ENTER_DURATION_MILLIS = 420
private const val SHARE_SECTION_EXIT_DURATION_MILLIS = 220
private const val SHARE_TRANSFER_ENTER_DURATION_MILLIS = 220
private const val SHARE_TRANSFER_EXIT_DURATION_MILLIS = 180
private const val SHARE_SECTION_OFFSET_DIVISOR = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
    onBackClick: () -> Unit,
    viewModel: ShareViewModel = hiltViewModel(),
) {
    val uiState = viewModel.collectShareScreenUiState()
    val localState = rememberShareScreenLocalState()
    val canSaveDeviceName = localState.canSaveDeviceName(uiState.deviceName)
    val dismissIme = rememberDismissImeAction()

    ShareScreenEffects(
        uiState = uiState,
        localState = localState,
        canSaveDeviceName = canSaveDeviceName,
        onClearPairingCodeError = viewModel::clearPairingCodeError,
    )

    ShareScreenContent(
        uiState = uiState,
        localState = localState,
        canSaveDeviceName = canSaveDeviceName,
        onBackClick = onBackClick,
        dismissIme = dismissIme,
        isTechnicalShareError = viewModel::isTechnicalShareError,
        onClearPairingCodeError = viewModel::clearPairingCodeError,
        onClearLanSharePairingCode = viewModel::clearLanSharePairingCode,
        onUpdateLanSharePairingCode = viewModel::updateLanSharePairingCode,
        onUpdateLanShareE2eEnabled = viewModel::updateLanShareE2eEnabled,
        onUpdateLanShareDeviceName = viewModel::updateLanShareDeviceName,
        onSendMemo = viewModel::sendMemo,
        onResetTransferState = viewModel::resetTransferState,
    )
}

@Composable
private fun ShareScreenContent(
    uiState: ShareScreenUiState,
    localState: ShareScreenLocalState,
    canSaveDeviceName: Boolean,
    onBackClick: () -> Unit,
    dismissIme: () -> Unit,
    isTechnicalShareError: (String) -> Boolean,
    onClearPairingCodeError: () -> Unit,
    onClearLanSharePairingCode: () -> Unit,
    onUpdateLanSharePairingCode: (String) -> Unit,
    onUpdateLanShareE2eEnabled: (Boolean) -> Unit,
    onUpdateLanShareDeviceName: (String) -> Unit,
    onSendMemo: (com.lomo.domain.model.DiscoveredDevice) -> Unit,
    onResetTransferState: () -> Unit,
) {
    val haptic = LocalAppHapticFeedback.current

    ShareScreenScaffold(
        onBackClick = onBackClick,
        hapticFeedback = haptic::medium,
    ) { padding ->
        ShareScreenBody(
            uiState = uiState,
            localState = localState,
            canSaveDeviceName = canSaveDeviceName,
            dismissIme = dismissIme,
            isTechnicalShareError = isTechnicalShareError,
            onUpdateLanShareE2eEnabled = onUpdateLanShareE2eEnabled,
            onOpenPairingDialog = {
                openPairingDialog(
                    localState = localState,
                    savedPairingCode = uiState.savedPairingCode,
                    onClearPairingCodeError = onClearPairingCodeError,
                )
            },
            onUpdateDeviceNameInput = { localState.deviceNameInput = it },
            onNameFieldFocusChanged = { isFocused -> localState.isDeviceNameFieldFocused = isFocused },
            onSaveDeviceName = {
                dismissIme()
                if (canSaveDeviceName) {
                    onUpdateLanShareDeviceName(localState.deviceNameInput)
                }
            },
            onUseSystemDeviceName = {
                dismissIme()
                localState.deviceNameInput = ""
                onUpdateLanShareDeviceName("")
            },
            onSendMemo = { device ->
                dismissIme()
                onSendMemo(device)
            },
            onDismissTransferState = onResetTransferState,
            modifier = Modifier.padding(padding),
        )
    }

    SharePairingDialog(
        uiState = uiState,
        localState = localState,
        isTechnicalMessage = isTechnicalShareError,
        onClearPairingCodeError = onClearPairingCodeError,
        onClearLanSharePairingCode = onClearLanSharePairingCode,
        onUpdateLanSharePairingCode = onUpdateLanSharePairingCode,
    )
}

@Composable
private fun ShareScreenEffects(
    uiState: ShareScreenUiState,
    localState: ShareScreenLocalState,
    canSaveDeviceName: Boolean,
    onClearPairingCodeError: () -> Unit,
) {
    LaunchedEffect(uiState.deviceName) {
        if (!(localState.isDeviceNameFieldFocused && canSaveDeviceName)) {
            localState.deviceNameInput = uiState.deviceName
        }
    }

    LaunchedEffect(Unit) {
        localState.showSettingsSection = true
        delay(SHARE_SETTINGS_STAGGER_DELAY_MILLIS)
        localState.showPreviewSection = true
        delay(SHARE_PREVIEW_STAGGER_DELAY_MILLIS)
        localState.showDevicesSection = true
    }

    LaunchedEffect(uiState.pairingRequiredEvent) {
        if (LanSharePairingDialogTriggerPolicy.shouldShowOnPairingRequiredEvent(uiState.pairingRequiredEvent)) {
            openPairingDialog(
                localState = localState,
                savedPairingCode = uiState.savedPairingCode,
                onClearPairingCodeError = onClearPairingCodeError,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareScreenScaffold(
    onBackClick: () -> Unit,
    hapticFeedback: () -> Unit,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.share_lan_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            hapticFeedback()
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
        content = content,
    )
}

@Composable
private fun ShareScreenBody(
    uiState: ShareScreenUiState,
    localState: ShareScreenLocalState,
    canSaveDeviceName: Boolean,
    dismissIme: () -> Unit,
    isTechnicalShareError: (String) -> Boolean,
    onUpdateLanShareE2eEnabled: (Boolean) -> Unit,
    onOpenPairingDialog: () -> Unit,
    onUpdateDeviceNameInput: (String) -> Unit,
    onNameFieldFocusChanged: (Boolean) -> Unit,
    onSaveDeviceName: () -> Unit,
    onUseSystemDeviceName: () -> Unit,
    onSendMemo: (com.lomo.domain.model.DiscoveredDevice) -> Unit,
    onDismissTransferState: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = AppSpacing.ScreenHorizontalPadding)
                .pointerInput(localState.isDeviceNameFieldFocused, canSaveDeviceName) {
                    detectTapGestures {
                        if (localState.isDeviceNameFieldFocused && !canSaveDeviceName) {
                            dismissIme()
                        }
                    }
                },
    ) {
        ShareSettingsSection(
            uiState = uiState,
            localState = localState,
            canSaveDeviceName = canSaveDeviceName,
            onUpdateLanShareE2eEnabled = onUpdateLanShareE2eEnabled,
            onOpenPairingDialog = onOpenPairingDialog,
            onUpdateDeviceNameInput = onUpdateDeviceNameInput,
            onNameFieldFocusChanged = onNameFieldFocusChanged,
            onSaveDeviceName = onSaveDeviceName,
            onUseSystemDeviceName = onUseSystemDeviceName,
        )
        Spacer(modifier = Modifier.height(AppSpacing.MediumSmall))
        SharePreviewSection(
            visible = localState.showPreviewSection,
            memoContent = uiState.memoContent,
        )
        Spacer(modifier = Modifier.height(AppSpacing.Medium))
        ShareTransferBannerSection(
            transferState = uiState.transferState,
            isTechnicalShareError = isTechnicalShareError,
            onDismissTransferState = onDismissTransferState,
        )
        DeviceDiscoverySection(
            showDevicesSection = localState.showDevicesSection,
            devices = uiState.discoveredDevices,
            transferState = uiState.transferState,
            onDeviceClick = onSendMemo,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ShareSettingsSection(
    uiState: ShareScreenUiState,
    localState: ShareScreenLocalState,
    canSaveDeviceName: Boolean,
    onUpdateLanShareE2eEnabled: (Boolean) -> Unit,
    onOpenPairingDialog: () -> Unit,
    onUpdateDeviceNameInput: (String) -> Unit,
    onNameFieldFocusChanged: (Boolean) -> Unit,
    onSaveDeviceName: () -> Unit,
    onUseSystemDeviceName: () -> Unit,
) {
    AnimatedVisibility(
        visible = localState.showSettingsSection,
        enter =
            fadeIn(tween(SHARE_SECTION_ENTER_DURATION_MILLIS)) +
                slideInVertically(
                    initialOffsetY = { -it / SHARE_SECTION_OFFSET_DIVISOR },
                    animationSpec = tween(SHARE_SECTION_ENTER_DURATION_MILLIS),
                ),
        exit =
            fadeOut(tween(SHARE_SECTION_EXIT_DURATION_MILLIS)) +
                shrinkVertically(animationSpec = tween(SHARE_SECTION_EXIT_DURATION_MILLIS)),
    ) {
        LanShareSettingsCard(
            e2eEnabled = uiState.e2eEnabled,
            pairingConfigured = uiState.pairingConfigured,
            deviceNameInput = localState.deviceNameInput,
            saveNameEnabled = canSaveDeviceName,
            onToggleE2e = { enabled ->
                onUpdateLanShareE2eEnabled(enabled)
                if (
                    LanSharePairingDialogTriggerPolicy.shouldShowOnE2eEnabled(
                        enabled = enabled,
                        pairingConfigured = uiState.pairingConfigured,
                    )
                ) {
                    onOpenPairingDialog()
                }
            },
            onOpenPairingDialog = onOpenPairingDialog,
            onDeviceNameInputChange = onUpdateDeviceNameInput,
            onNameFieldFocusChanged = onNameFieldFocusChanged,
            onSaveDeviceName = onSaveDeviceName,
            onUseSystemDeviceName = onUseSystemDeviceName,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SharePreviewSection(
    visible: Boolean,
    memoContent: String,
) {
    AnimatedVisibility(
        visible = visible,
        enter =
            fadeIn(tween(SHARE_SECTION_ENTER_DURATION_MILLIS)) +
                slideInVertically(
                    initialOffsetY = { it / SHARE_SECTION_OFFSET_DIVISOR },
                    animationSpec = tween(SHARE_SECTION_ENTER_DURATION_MILLIS),
                ),
        exit =
            fadeOut(tween(SHARE_SECTION_EXIT_DURATION_MILLIS)) +
                shrinkVertically(animationSpec = tween(SHARE_SECTION_EXIT_DURATION_MILLIS)),
    ) {
        MemoPreviewCard(
            content = memoContent,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ShareTransferBannerSection(
    transferState: ShareTransferState,
    isTechnicalShareError: (String) -> Boolean,
    onDismissTransferState: () -> Unit,
) {
    AnimatedVisibility(
        visible = transferState !is ShareTransferState.Idle,
        enter =
            fadeIn(tween(SHARE_TRANSFER_ENTER_DURATION_MILLIS)) +
                expandVertically(animationSpec = tween(SHARE_TRANSFER_ENTER_DURATION_MILLIS)),
        exit =
            fadeOut(tween(SHARE_TRANSFER_EXIT_DURATION_MILLIS)) +
                shrinkVertically(animationSpec = tween(SHARE_TRANSFER_EXIT_DURATION_MILLIS)),
    ) {
        TransferStateBanner(
            state = transferState,
            isTechnicalMessage = isTechnicalShareError,
            onDismiss = onDismissTransferState,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = AppSpacing.MediumSmall),
        )
    }
}

@Composable
private fun SharePairingDialog(
    uiState: ShareScreenUiState,
    localState: ShareScreenLocalState,
    isTechnicalMessage: (String) -> Boolean,
    onClearPairingCodeError: () -> Unit,
    onClearLanSharePairingCode: () -> Unit,
    onUpdateLanSharePairingCode: (String) -> Unit,
) {
    ShareDialogHost(
        visible = localState.showPairingDialog,
        pairingCodeInput = localState.pairingCodeInput,
        pairingCodeVisible = localState.pairingCodeVisible,
        pairingCodeError = uiState.pairingCodeError,
        pairingConfigured = uiState.pairingConfigured,
        isTechnicalMessage = isTechnicalMessage,
        onDismiss = {
            onClearPairingCodeError()
            localState.showPairingDialog = false
        },
        onPairingCodeInputChange = {
            localState.pairingCodeInput = it
            if (uiState.pairingCodeError != null) {
                onClearPairingCodeError()
            }
        },
        onToggleVisibility = { localState.pairingCodeVisible = !localState.pairingCodeVisible },
        onClearPairingCode = {
            onClearLanSharePairingCode()
            localState.showPairingDialog = false
        },
        onSave = {
            onUpdateLanSharePairingCode(localState.pairingCodeInput)
            if (LanSharePairingCodePolicy.shouldDismissDialogAfterSave(localState.pairingCodeInput)) {
                localState.showPairingDialog = false
            }
        },
    )
}

private fun openPairingDialog(
    localState: ShareScreenLocalState,
    savedPairingCode: String,
    onClearPairingCodeError: () -> Unit,
) {
    localState.pairingCodeInput = savedPairingCode
    localState.pairingCodeVisible = false
    onClearPairingCodeError()
    localState.showPairingDialog = true
}

@Composable
private fun rememberDismissImeAction(): () -> Unit {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    return {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }
}
