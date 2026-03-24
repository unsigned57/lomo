package com.lomo.app.feature.share

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.ShareTransferState
import com.lomo.ui.component.common.ExpressiveLoadingIndicator
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing

private const val DEVICE_DISCOVERY_ENTER_DURATION_MILLIS = 420
private const val DEVICE_DISCOVERY_EXIT_DURATION_MILLIS = 220
private const val DEVICE_DISCOVERY_CONTENT_ENTER_DURATION_MILLIS = 260
private const val DEVICE_DISCOVERY_OFFSET_DIVISOR = 4
private val DEVICE_DISCOVERY_ICON_SIZE = 20.dp
private val DEVICE_DISCOVERY_LOADER_SIZE = 14.dp

private val DEVICE_CARD_AVATAR_SIZE = 44.dp
private val DEVICE_CARD_AVATAR_ICON_SIZE = 22.dp
private val DEVICE_CARD_STATUS_DOT_SIZE = 12.dp
private val DEVICE_CARD_STATUS_DOT_PADDING = 2.dp
private val DEVICE_CARD_TRAILING_ICON_SIZE = 20.dp

private const val SEARCH_PULSE_INITIAL_ALPHA = 0.3f
private const val SEARCH_PULSE_TARGET_ALPHA = 1f
private const val SEARCH_PULSE_INITIAL_SCALE = 0.95f
private const val SEARCH_PULSE_TARGET_SCALE = 1.05f
private const val SEARCH_PULSE_DURATION_MILLIS = 1_200
private val SEARCH_PULSE_ICON_SIZE = 64.dp
private const val SEARCHING_HINT_ALPHA = 0.7f

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
        enter =
            fadeIn(tween(DEVICE_DISCOVERY_ENTER_DURATION_MILLIS)) +
                slideInVertically(
                    initialOffsetY = { it / DEVICE_DISCOVERY_OFFSET_DIVISOR },
                    animationSpec = tween(DEVICE_DISCOVERY_ENTER_DURATION_MILLIS),
                ),
        exit = fadeOut(tween(DEVICE_DISCOVERY_EXIT_DURATION_MILLIS)),
    ) {
        DeviceDiscoveryHeader(devices = devices)
    }

    val listState = rememberLazyListState()
    AnimatedContent(
        targetState = devices.isEmpty(),
        transitionSpec = {
            fadeIn(tween(DEVICE_DISCOVERY_CONTENT_ENTER_DURATION_MILLIS)) togetherWith
                fadeOut(tween(DEVICE_DISCOVERY_EXIT_DURATION_MILLIS))
        },
        label = "device-list",
        modifier = modifier,
    ) { isEmpty ->
        if (isEmpty) {
            DeviceSearchingState(modifier = Modifier.fillMaxSize())
        } else {
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = AppShapes.Medium,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeviceCardAvatar()
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
                modifier = Modifier.size(DEVICE_CARD_TRAILING_ICON_SIZE),
            )
        }
    }
}

@Composable
private fun DeviceSearchingState(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "search-pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = SEARCH_PULSE_INITIAL_ALPHA,
        targetValue = SEARCH_PULSE_TARGET_ALPHA,
        animationSpec =
            infiniteRepeatable(
                animation = tween(SEARCH_PULSE_DURATION_MILLIS, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "pulse-alpha",
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = SEARCH_PULSE_INITIAL_SCALE,
        targetValue = SEARCH_PULSE_TARGET_SCALE,
        animationSpec =
            infiniteRepeatable(
                animation = tween(SEARCH_PULSE_DURATION_MILLIS, easing = LinearEasing),
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
                    .size(SEARCH_PULSE_ICON_SIZE)
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
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = SEARCHING_HINT_ALPHA),
        )
    }
}

@Composable
private fun DeviceDiscoveryHeader(devices: List<DiscoveredDevice>) {
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
                text = stringResource(R.string.share_devices_count, devices.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (devices.isEmpty()) {
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
private fun DeviceDiscoveryList(
    devices: List<DiscoveredDevice>,
    transferState: ShareTransferState,
    onDeviceClick: (DiscoveredDevice) -> Unit,
    listState: LazyListState,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.MediumSmall),
    ) {
        itemsIndexed(devices, key = { _, item -> "${item.host}:${item.port}" }) { _, device ->
            DeviceCard(
                device = device,
                isEnabled = transferState.allowsDeviceSelection(),
                onClick = { onDeviceClick(device) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

private fun ShareTransferState.allowsDeviceSelection(): Boolean =
    this is ShareTransferState.Idle ||
        this is ShareTransferState.Success ||
        this is ShareTransferState.Error

@Composable
private fun DeviceCardAvatar() {
    Box {
        Surface(
            modifier = Modifier.size(DEVICE_CARD_AVATAR_SIZE),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = CircleShape,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.PhoneAndroid,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(DEVICE_CARD_AVATAR_ICON_SIZE),
                )
            }
        }
        Box(
            modifier =
                Modifier
                    .size(DEVICE_CARD_STATUS_DOT_SIZE)
                    .align(Alignment.BottomEnd)
                    .background(
                        color = MaterialTheme.colorScheme.surface,
                        shape = CircleShape,
                    ).padding(DEVICE_CARD_STATUS_DOT_PADDING)
                    .background(
                        color = MaterialTheme.colorScheme.tertiary,
                        shape = CircleShape,
                    ),
        )
    }
}
