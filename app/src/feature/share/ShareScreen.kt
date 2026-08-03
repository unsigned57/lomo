package com.lomo.app.feature.share

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
import androidx.compose.ui.res.stringResource
import com.lomo.app.CapabilityRecoveryAction
import com.lomo.app.R
import com.lomo.app.util.injectedKoinViewModel
import com.lomo.domain.model.ShareTransferState
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.util.LocalAppHapticFeedback

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
    onBackClick: () -> Unit,
    viewModel: ShareViewModel = injectedKoinViewModel(),
) {
    val uiState = collectShareScreenUiState(viewModel)
    val localState = rememberShareScreenLocalState()
    val canSaveDeviceName = localState.canSaveDeviceName(uiState.deviceName)
    val recoveryExecutor = rememberCapabilityRecoveryExecutor()
    val requestPermissions = rememberLanShareNetworkPermissionRequester(
        shouldRequestPermissions = uiState.lanShareEnabled,
        onPermissionGranted = viewModel.onLanShareNetworkPermissionsGranted,
        onPermissionDenied = viewModel.onLanShareNetworkPermissionsDenied,
    )
    val haptic = LocalAppHapticFeedback.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    LaunchedEffect(uiState.deviceName) {
        if (!localState.isDeviceNameFieldFocused) localState.deviceNameInput = uiState.deviceName
    }
    LaunchedEffect(Unit) { viewModel.startLanShareDiscoverySession() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.share_lan_title)) },
                navigationIcon = {
                    IconButton(onClick = { haptic.medium(); onBackClick() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
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
                    .padding(horizontal = AppSpacing.ScreenHorizontalPadding),
        ) {
            LanShareSettingsCard(
                deviceNameInput = localState.deviceNameInput,
                saveNameEnabled = canSaveDeviceName,
                onDeviceNameInputChange = { localState.deviceNameInput = it },
                onNameFieldFocusChanged = { localState.isDeviceNameFieldFocused = it },
                onSaveDeviceName = {
                    if (canSaveDeviceName) {
                        viewModel.updateLanShareDeviceName(localState.deviceNameInput)
                    }
                },
                onUseSystemDeviceName = { localState.deviceNameInput = ""; viewModel.updateLanShareDeviceName("") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(AppSpacing.MediumSmall))
            MemoPreviewCard(uiState.memoContent, Modifier.fillMaxWidth())
            Spacer(Modifier.height(AppSpacing.Medium))
            if (uiState.transferState !is ShareTransferState.Idle) {
                TransferStateBanner(
                    state = uiState.transferState,
                    isTechnicalMessage = viewModel.isTechnicalShareError,
                    onDismiss = viewModel::resetTransferState,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(AppSpacing.MediumSmall))
            }
            DeviceDiscoverySection(
                showDevicesSection = true,
                devices = uiState.discoveredDevices,
                lanShareEnabled = uiState.lanShareEnabled,
                permissionState = uiState.lanSharePermissionState,
                discoveryError = uiState.lanShareDiscoveryError,
                diagnostics = uiState.lanShareDiscoveryDiagnostics,
                transferState = uiState.transferState,
                onRequestLanSharePermissions = requestPermissions,
                onExecuteRecoveryAction = { action: CapabilityRecoveryAction -> recoveryExecutor.execute(action) },
                onDeviceClick = viewModel::sendMemo,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
