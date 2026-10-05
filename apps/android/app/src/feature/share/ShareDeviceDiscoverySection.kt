package com.lomo.app.feature.share

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lomo.app.CapabilityRecoveryAction
import com.lomo.app.CapabilityRecoveryDecision
import com.lomo.app.R
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.LanShareDiscoveryDiagnostics
import com.lomo.domain.model.ShareTransferState
import com.lomo.ui.component.common.ExpressiveLoadingIndicator
import com.lomo.ui.theme.AppSpacing
import kotlinx.collections.immutable.ImmutableList
private const val DEVICE_DISCOVERY_OFFSET_DIVISOR = 4
private val DEVICE_DISCOVERY_ICON_SIZE = 20.dp
private val DEVICE_DISCOVERY_LOADER_SIZE = 14.dp
private val SEARCH_PULSE_ICON_SIZE = 64.dp
private const val SEARCHING_HINT_ALPHA = 0.7f

internal data class DeviceDiscoveryRecoveryAffordance(
    val showRetry: Boolean,
    val fallbackAction: CapabilityRecoveryAction?,
)

internal fun resolveDeviceDiscoveryRecoveryAffordance(
    fallbackAction: CapabilityRecoveryAction?,
    canRetryAfterRecovery: Boolean,
): DeviceDiscoveryRecoveryAffordance =
    DeviceDiscoveryRecoveryAffordance(
        showRetry = canRetryAfterRecovery,
        fallbackAction = fallbackAction,
    )

@Composable
internal fun DeviceDiscoverySection(
    showDevicesSection: Boolean,
    devices: ImmutableList<DiscoveredDevice>,
    lanShareEnabled: Boolean,
    permissionState: LanSharePermissionState,
    discoveryError: String?,
    diagnostics: LanShareDiscoveryDiagnostics,
    transferState: ShareTransferState,
    onRequestLanSharePermissions: () -> Unit,
    onExecuteRecoveryAction: (CapabilityRecoveryAction) -> Unit,
    onDeviceClick: (DiscoveredDevice) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motionScheme = MaterialTheme.motionScheme
    val contentState =
        resolveDeviceDiscoveryContentState(
            discoveredDeviceCount = devices.size,
            permissionState = permissionState,
            discoveryError = discoveryError,
            diagnostics = diagnostics,
        )
    val searchHint = resolveDeviceDiscoverySearchHint(diagnostics)
    AnimatedVisibility(
        visible = showDevicesSection,
        enter =
            fadeIn(motionScheme.defaultEffectsSpec()) +
                slideInVertically(
                    initialOffsetY = { it / DEVICE_DISCOVERY_OFFSET_DIVISOR },
                    animationSpec = motionScheme.defaultSpatialSpec(),
                ),
        exit = fadeOut(motionScheme.defaultEffectsSpec()),
    ) {
        DeviceDiscoveryHeader(
            devices = devices,
            showSearchingIndicator = lanShareEnabled && contentState == DeviceDiscoveryContentState.Searching,
        )
    }

    val listState = rememberLazyListState()
    AnimatedContent(
        targetState = contentState,
        transitionSpec = {
            fadeIn(motionScheme.defaultEffectsSpec()) togetherWith
                fadeOut(motionScheme.defaultEffectsSpec())
        },
        label = "device-list",
        modifier = modifier,
    ) { state ->
        when (state) {
            DeviceDiscoveryContentState.Searching ->
                DeviceSearchingState(
                    searchHint = searchHint,
                    modifier = Modifier.fillMaxSize(),
                )
            DeviceDiscoveryContentState.PermissionDenied ->
                DevicePermissionRequiredState(
                    recoveryAffordance =
                        resolveDeviceDiscoveryRecoveryAffordance(
                            fallbackAction = lanSharePermissionRecoveryAction(CapabilityRecoveryDecision.Denied),
                            canRetryAfterRecovery = canRetryLanSharePermissionRecovery(),
                        ),
                    onRequestLanSharePermissions = onRequestLanSharePermissions,
                    onExecuteRecoveryAction = onExecuteRecoveryAction,
                    modifier = Modifier.fillMaxSize(),
                )
            DeviceDiscoveryContentState.StartupFailed ->
                DeviceDiscoveryErrorState(
                    message = discoveryError.orEmpty(),
                    onRetry = onRequestLanSharePermissions,
                    modifier = Modifier.fillMaxSize(),
                )
            DeviceDiscoveryContentState.Devices ->
                DeviceDiscoveryList(
                    devices = devices,
                    transferState = transferState,
                    onDeviceClick = onDeviceClick,
                    listState = listState,
                )
        }
    }
}

@Composable
private fun DeviceSearchingState(
    searchHint: DeviceDiscoverySearchHint,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ExpressiveLoadingIndicator(modifier = Modifier.size(SEARCH_PULSE_ICON_SIZE))
        Spacer(modifier = Modifier.height(AppSpacing.Medium))
        Text(
            text = stringResource(R.string.share_searching_devices),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(AppSpacing.ExtraSmall))
        Text(
            text = stringResource(searchHint.stringResId()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = SEARCHING_HINT_ALPHA),
        )
    }
}

private fun DeviceDiscoverySearchHint.stringResId(): Int =
    when (this) {
        DeviceDiscoverySearchHint.SameWifi -> R.string.share_searching_hint
        DeviceDiscoverySearchHint.ProbeBackoff -> R.string.share_searching_probe_backoff_hint
        DeviceDiscoverySearchHint.DegradedRoute -> R.string.share_searching_degraded_route_hint
    }

@Composable
private fun DeviceDiscoveryHeader(
    devices: ImmutableList<DiscoveredDevice>,
    showSearchingIndicator: Boolean,
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
                modifier = Modifier.size(DEVICE_DISCOVERY_ICON_SIZE),
            )
            Spacer(modifier = Modifier.width(AppSpacing.Small))
            Text(
                text = stringResource(R.string.share_nearby_devices),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = pluralStringResource(R.plurals.share_devices_count, devices.size, devices.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showSearchingIndicator) {
                Spacer(modifier = Modifier.width(AppSpacing.Small))
                ExpressiveLoadingIndicator(
                    modifier = Modifier.size(DEVICE_DISCOVERY_LOADER_SIZE),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(modifier = Modifier.height(AppSpacing.MediumSmall))
    }
}

@Composable
private fun DevicePermissionRequiredState(
    recoveryAffordance: DeviceDiscoveryRecoveryAffordance,
    onRequestLanSharePermissions: () -> Unit,
    onExecuteRecoveryAction: (CapabilityRecoveryAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fallbackAction = recoveryAffordance.fallbackAction
    DeviceDiscoveryPlaceholder(
        title = stringResource(R.string.share_permission_required_title),
        message = stringResource(R.string.share_permission_required_hint),
        primaryActionLabel = stringResource(R.string.action_retry).takeIf { recoveryAffordance.showRetry },
        onPrimaryAction = onRequestLanSharePermissions.takeIf { recoveryAffordance.showRetry },
        secondaryActionLabel = recoveryActionLabel(fallbackAction),
        onSecondaryAction = fallbackAction?.let { action -> { onExecuteRecoveryAction(action) } },
        modifier = modifier,
    )
}

@Composable
private fun recoveryActionLabel(action: CapabilityRecoveryAction?): String? =
    when (action) {
        CapabilityRecoveryAction.OpenAppSettings -> stringResource(R.string.share_open_settings)
        CapabilityRecoveryAction.RequestRuntimePermissions,
        is CapabilityRecoveryAction.OpenSettings,
        CapabilityRecoveryAction.SelectSafTree,
        CapabilityRecoveryAction.SelectSafDocument,
        null,
        -> null
    }

@Composable
private fun DeviceDiscoveryErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DeviceDiscoveryPlaceholder(
        title = message,
        message = stringResource(R.string.share_searching_hint),
        primaryActionLabel = stringResource(R.string.action_retry),
        onPrimaryAction = onRetry,
        modifier = modifier,
    )
}

@Composable
private fun DeviceDiscoveryPlaceholder(
    title: String,
    message: String,
    primaryActionLabel: String?,
    onPrimaryAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.padding(horizontal = AppSpacing.Large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.WifiFind,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(SEARCH_PULSE_ICON_SIZE),
        )
        Spacer(modifier = Modifier.height(AppSpacing.Medium))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(AppSpacing.ExtraSmall))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = SEARCHING_HINT_ALPHA),
        )
        Spacer(modifier = Modifier.height(AppSpacing.Medium))
        Row(
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (primaryActionLabel != null && onPrimaryAction != null) {
                Button(onClick = onPrimaryAction) {
                    Text(primaryActionLabel)
                }
            }
            if (secondaryActionLabel != null && onSecondaryAction != null) {
                TextButton(onClick = onSecondaryAction) {
                    Text(secondaryActionLabel)
                }
            }
        }
    }
}
