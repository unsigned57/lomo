package com.lomo.app.feature.settings

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.domain.model.SyncBackendType
import com.lomo.ui.component.common.LomoFilledTonalButton
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.LocalLomoMotion
import com.lomo.ui.util.LocalAppHapticFeedback

@Composable
internal fun SettingsHomeHero(heroState: SettingsHomeHeroState, onSyncNow: () -> Unit, modifier: Modifier = Modifier) {
    val layoutMotion = LocalLomoMotion.current.layout
    val activeState = heroState as? SettingsHomeHeroState.Active
    AnimatedVisibility(
        visible = activeState != null,
        modifier = modifier,
        enter =
        expandVertically(animationSpec = layoutMotion.defaultSpatialSpec()) +
            fadeIn(animationSpec = layoutMotion.defaultEffectsSpec()),
        exit =
        shrinkVertically(animationSpec = layoutMotion.fastSpatialSpec()) +
            fadeOut(animationSpec = layoutMotion.fastEffectsSpec()),
        label = "SettingsHomeHeroVisibility",
    ) {
        if (activeState != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                shape = AppShapes.ExtraLargeIncreased,
            ) {
                Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                    SettingsHomeHeroActiveContent(
                        state = activeState,
                        onSyncNow = onSyncNow,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsHomeHeroActiveContent(state: SettingsHomeHeroState.Active, onSyncNow: () -> Unit) {
    val haptic = LocalAppHapticFeedback.current
    val titleText = settingsHomeHeroTitleText(state)
    val providersText = state.activeProviders.joinToString(separator = " · ", transform = ::providerLabel)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier =
            Modifier
                .size(44.dp)
                .clip(AppShapes.Large)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudDone,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(modifier = Modifier.size(AppSpacing.Medium))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = providersText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.size(AppSpacing.Small))
        LomoFilledTonalButton(
            onClick = {
                haptic.medium()
                onSyncNow()
            },
            enabled = !state.isCurrentlySyncing,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.size(AppSpacing.ExtraSmall))
            Text(
                text = stringResource(R.string.settings_home_hero_sync_now),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun settingsHomeHeroTitleText(state: SettingsHomeHeroState.Active): String = when {
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

private fun providerLabel(provider: SyncBackendType): String = when (provider) {
    SyncBackendType.GIT -> "Git"

    SyncBackendType.WEBDAV -> "WebDAV"

    SyncBackendType.S3 -> "S3"

    SyncBackendType.INBOX -> "Inbox"

    SyncBackendType.NONE,
    SyncBackendType.UNKNOWN,
    -> ""
}
