package com.lomo.app.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.ui.component.settings.PreferenceItem
import com.lomo.ui.component.settings.SettingsGroup
import com.lomo.ui.component.settings.SwitchPreferenceItem
import com.lomo.ui.icon.LomoIcons

@Composable
fun LanShareSettingsSection(
    state: LanShareSectionState,
    onToggleEnabled: (Boolean) -> Unit,
    onOpenDeviceNameDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsGroup(
        title = stringResource(R.string.share_lan_title),
        modifier = modifier,
    ) {
        item {
            SwitchPreferenceItem(
                title = stringResource(R.string.settings_lan_share_enabled_title),
                subtitle = stringResource(R.string.settings_lan_share_enabled_subtitle),
                icon = LomoIcons.PhoneAndroid,
                checked = state.enabled,
                onCheckedChange = onToggleEnabled,
            )
        }
        expandable(
            visible = state.enabled,
            label = "LanShareSettingsVisibility",
        ) {
            item {
                PreferenceItem(
                    title = stringResource(R.string.share_device_name_label),
                    subtitle = state.deviceName.ifBlank { stringResource(R.string.settings_not_set) },
                    icon = LomoIcons.PhoneAndroid,
                    onClick = onOpenDeviceNameDialog,
                )
            }
        }
    }
}
