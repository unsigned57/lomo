package com.lomo.app.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
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
    onToggleEnabled: (Boolean) -> Unit,
    onOpenDeviceNameDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsGroup(
        title = stringResource(R.string.share_lan_title),
        modifier = modifier,
    ) {
        SwitchPreferenceItem(
            title = stringResource(R.string.settings_lan_share_enabled_title),
            subtitle = stringResource(R.string.settings_lan_share_enabled_subtitle),
            icon = Icons.Outlined.PhoneAndroid,
            checked = state.enabled,
            onCheckedChange = onToggleEnabled,
        )
        SettingsExpandableContent(
            visible = state.enabled,
            label = "LanShareSettingsVisibility",
        ) {
            Column {
                SettingsDivider()
                PreferenceItem(
                    title = stringResource(R.string.share_device_name_label),
                    subtitle = state.deviceName.ifBlank { stringResource(R.string.settings_not_set) },
                    icon = Icons.Outlined.PhoneAndroid,
                    onClick = onOpenDeviceNameDialog,
                )
            }
        }
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
