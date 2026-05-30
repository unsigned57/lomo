package com.lomo.app.feature.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.domain.model.SyncBackendType
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlin.random.Random

private val SettingsHomeHeroTipResources: ImmutableList<Int> =
    persistentListOf(
        R.string.input_hint_1,
        R.string.input_hint_2,
        R.string.input_hint_3,
        R.string.input_hint_4,
        R.string.input_hint_5,
        R.string.input_hint_6,
        R.string.input_hint_7,
    )

@Composable
internal fun SettingsHomeHero(
    heroState: SettingsHomeHeroState,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = AppShapes.ExtraLargeIncreased,
    ) {
        Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            when (heroState) {
                is SettingsHomeHeroState.Active -> SettingsHomeHeroActiveContent(
                    state = heroState,
                    onSyncNow = onSyncNow,
                )

                SettingsHomeHeroState.NotConfigured -> SettingsHomeHeroTipContent()
            }
        }
    }
}

@Composable
private fun SettingsHomeHeroActiveContent(
    state: SettingsHomeHeroState.Active,
    onSyncNow: () -> Unit,
) {
    val haptic = LocalAppHapticFeedback.current
    val titleText = settingsHomeHeroTitleText(state)
    val providersText = state.activeProviders.joinToString(separator = " · ", transform = ::providerLabel)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Outlined.CloudDone,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
        )
        Spacer(modifier = Modifier.size(AppSpacing.Medium))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = providersText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
            )
        }
        Spacer(modifier = Modifier.size(AppSpacing.Small))
        FilledTonalButton(
            onClick = {
                haptic.medium()
                onSyncNow()
            },
            enabled = !state.isCurrentlySyncing,
        ) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.size(AppSpacing.ExtraSmall))
            Text(stringResource(R.string.settings_home_hero_sync_now))
        }
    }
}

@Composable
private fun SettingsHomeHeroTipContent() {
    val haptic = LocalAppHapticFeedback.current
    val totalTips = SettingsHomeHeroTipResources.size
    val initialIndex = remember { Random.nextInt(totalTips) }
    var tipIndex by rememberSaveable { mutableIntStateOf(initialIndex) }
    val tipText = stringResource(SettingsHomeHeroTipResources[tipIndex % totalTips])

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Outlined.Lightbulb,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
        )
        Spacer(modifier = Modifier.size(AppSpacing.Medium))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_home_hero_tip_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = tipText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
            )
        }
        Spacer(modifier = Modifier.size(AppSpacing.Small))
        IconButton(
            onClick = {
                haptic.medium()
                tipIndex = (tipIndex + 1) % totalTips
            },
        ) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = stringResource(R.string.settings_home_hero_tip_next),
            )
        }
    }
}

@Composable
private fun settingsHomeHeroTitleText(state: SettingsHomeHeroState.Active): String =
    when {
        state.isCurrentlySyncing -> stringResource(R.string.settings_home_hero_syncing)
        state.lastSuccessfulSyncMillis == null -> stringResource(R.string.settings_home_hero_never_synced)
        else -> {
            val relative =
                DateUtils
                    .getRelativeTimeSpanString(
                        state.lastSuccessfulSyncMillis,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                    ).toString()
            stringResource(R.string.settings_home_hero_synced, relative)
        }
    }

private fun providerLabel(provider: SyncBackendType): String =
    when (provider) {
        SyncBackendType.GIT -> "Git"
        SyncBackendType.WEBDAV -> "WebDAV"
        SyncBackendType.S3 -> "S3"
        SyncBackendType.INBOX -> "Inbox"
        SyncBackendType.NONE -> ""
    }
