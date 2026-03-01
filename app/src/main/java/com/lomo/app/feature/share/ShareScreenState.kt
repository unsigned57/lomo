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

data class ShareScreenUiState(
    val discoveredDevices: List<DiscoveredDevice>,
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

    fun canSaveDeviceName(currentDeviceName: String): Boolean =
        deviceNameInput.trim() != currentDeviceName.trim()
}

@Composable
fun rememberShareScreenLocalState(): ShareScreenLocalState = remember { ShareScreenLocalState() }

@Composable
fun ShareViewModel.collectShareScreenUiState(): ShareScreenUiState {
    val devices = discoveredDevices.collectAsStateWithLifecycle().value
    val transfer = transferState.collectAsStateWithLifecycle().value
    val e2e = lanShareE2eEnabled.collectAsStateWithLifecycle().value
    val pairingConfiguredValue = lanSharePairingConfigured.collectAsStateWithLifecycle().value
    val savedPairingCodeValue = lanSharePairingCode.collectAsStateWithLifecycle().value
    val pairingCodeErrorValue = pairingCodeError.collectAsStateWithLifecycle().value
    val pairingRequiredValue = pairingRequiredEvent.collectAsStateWithLifecycle().value
    val deviceNameValue = lanShareDeviceName.collectAsStateWithLifecycle().value

    return ShareScreenUiState(
        discoveredDevices = devices,
        transferState = transfer,
        e2eEnabled = e2e,
        pairingConfigured = pairingConfiguredValue,
        savedPairingCode = savedPairingCodeValue,
        pairingCodeError = pairingCodeErrorValue,
        pairingRequiredEvent = pairingRequiredValue,
        deviceName = deviceNameValue,
        memoContent = memoContent,
    )
}
