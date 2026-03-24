package com.lomo.app.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.ui.component.settings.PreferenceItem
import com.lomo.ui.component.settings.SettingsGroup
import com.lomo.ui.component.settings.SwitchPreferenceItem
import com.lomo.ui.theme.MotionTokens

@Composable
fun LanShareSettingsSection(
    state: LanShareSectionState,
    onToggleE2e: (Boolean) -> Unit,
    onOpenPairingDialog: () -> Unit,
    onOpenDeviceNameDialog: () -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.share_lan_title)) {
        SwitchPreferenceItem(
            title = stringResource(R.string.share_e2e_enabled_title),
            subtitle = stringResource(R.string.share_e2e_enabled_subtitle),
            icon = Icons.Default.Lock,
            checked = state.e2eEnabled,
            onCheckedChange = onToggleE2e,
        )
        SettingsExpandableContent(
            visible = state.e2eEnabled,
            label = "LanPairingVisibility",
        ) {
            LanSharePairingSettings(
                pairingConfigured = state.pairingConfigured,
                onOpenPairingDialog = onOpenPairingDialog,
            )
        }
        SettingsDivider()
        PreferenceItem(
            title = stringResource(R.string.share_device_name_label),
            subtitle = state.deviceName.ifBlank { stringResource(R.string.settings_not_set) },
            icon = Icons.Outlined.PhoneAndroid,
            onClick = onOpenDeviceNameDialog,
        )
    }
}

@Composable
private fun LanSharePairingSettings(
    pairingConfigured: Boolean,
    onOpenPairingDialog: () -> Unit,
) {
    Column {
        SettingsDivider()
        PreferenceItem(
            title = stringResource(R.string.settings_lan_share_pairing_code),
            subtitle =
                stringResource(
                    if (pairingConfigured) {
                        R.string.settings_lan_share_pairing_configured
                    } else {
                        R.string.settings_lan_share_pairing_not_set
                    },
                ),
            icon = Icons.Default.Lock,
            onClick = onOpenPairingDialog,
        )
    }
}

@Composable
internal fun SettingsExpandableContent(
    visible: Boolean,
    label: String,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter =
            expandVertically(
                animationSpec =
                    tween(
                        durationMillis = MotionTokens.DurationMedium2,
                        easing = MotionTokens.EasingEmphasizedDecelerate,
                    ),
            ) +
                fadeIn(
                    animationSpec =
                        tween(
                            durationMillis = MotionTokens.DurationMedium2,
                            easing = MotionTokens.EasingEmphasizedDecelerate,
                        ),
                ),
        exit =
            shrinkVertically(
                animationSpec =
                    tween(
                        durationMillis = MotionTokens.DurationShort4,
                        easing = MotionTokens.EasingEmphasizedAccelerate,
                    ),
            ) +
                fadeOut(
                    animationSpec =
                        tween(
                            durationMillis = MotionTokens.DurationShort4,
                            easing = MotionTokens.EasingEmphasizedAccelerate,
                        ),
                ),
        label = label,
        content = { content() },
    )
}
