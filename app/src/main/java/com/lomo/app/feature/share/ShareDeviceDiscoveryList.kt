package com.lomo.app.feature.share

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.ShareTransferState
import com.lomo.ui.component.common.lomoListItemMotion
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList

private val DEVICE_CARD_AVATAR_SIZE = 44.dp
private val DEVICE_CARD_AVATAR_ICON_SIZE = 22.dp
private val DEVICE_CARD_STATUS_DOT_SIZE = 12.dp
private val DEVICE_CARD_STATUS_DOT_PADDING = 2.dp
private val DEVICE_CARD_TRAILING_ICON_SIZE = 20.dp

@Composable
internal fun DeviceDiscoveryList(
    devices: ImmutableList<DiscoveredDevice>,
    transferState: ShareTransferState,
    onDeviceClick: (DiscoveredDevice) -> Unit,
    listState: LazyListState,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.MediumSmall),
    ) {
        itemsIndexed(devices, key = { _, item -> item.motionKey() }) { _, device ->
            DeviceCard(
                device = device,
                isEnabled = transferState.allowsDeviceSelection(),
                onClick = { onDeviceClick(device) },
                modifier =
                    Modifier.lomoListItemMotion(this),
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

private fun DiscoveredDevice.motionKey(): String = "$host:$port"

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
