package com.lomo.app.feature.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.ShareTransferState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

data class ShareScreenUiState(
    val discoveredDevices: ImmutableList<DiscoveredDevice>,
    val transferState: ShareTransferState,
    val e2eEnabled: Boolean,
    val pairingConfigured: Boolean,
    val savedPairingCode: String,
    val pairingCodeError: String?,
    val pairingRequiredEvent: Int,
    val deviceName: String,
    val memoContent: String,
)

@Stable
class ShareScreenLocalState {
    var showPairingDialog by mutableStateOf(false)
    var pairingCodeInput by mutableStateOf("")
    var pairingCodeVisible by mutableStateOf(false)
    var deviceNameInput by mutableStateOf("")

    var showSettingsSection by mutableStateOf(false)
    var showPreviewSection by mutableStateOf(false)
    var showDevicesSection by mutableStateOf(false)
    var isDeviceNameFieldFocused by mutableStateOf(false)

    fun canSaveDeviceName(currentDeviceName: String): Boolean = deviceNameInput.trim() != currentDeviceName.trim()
}

@Composable
fun rememberShareScreenLocalState(): ShareScreenLocalState = remember { ShareScreenLocalState() }

@Composable
fun collectShareScreenUiState(viewModel: ShareViewModel): ShareScreenUiState {
    val devices = viewModel.discoveredDevices.collectAsStateWithLifecycle().value
    val transfer = viewModel.transferState.collectAsStateWithLifecycle().value
    val e2e = viewModel.lanShareE2eEnabled.collectAsStateWithLifecycle().value
    val pairingConfiguredValue = viewModel.lanSharePairingConfigured.collectAsStateWithLifecycle().value
    val savedPairingCodeValue = viewModel.lanSharePairingCode.collectAsStateWithLifecycle().value
    val pairingCodeErrorValue = viewModel.pairingCodeError.collectAsStateWithLifecycle().value
    val pairingRequiredValue = viewModel.pairingRequiredEvent.collectAsStateWithLifecycle().value
    val deviceNameValue = viewModel.lanShareDeviceName.collectAsStateWithLifecycle().value

    return ShareScreenUiState(
        discoveredDevices = devices.toImmutableList(),
        transferState = transfer,
        e2eEnabled = e2e,
        pairingConfigured = pairingConfiguredValue,
        savedPairingCode = savedPairingCodeValue,
        pairingCodeError = pairingCodeErrorValue,
        pairingRequiredEvent = pairingRequiredValue,
        deviceName = deviceNameValue,
        memoContent = viewModel.memoContent,
    )
}
