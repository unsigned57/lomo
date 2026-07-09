package com.lomo.app.feature.settings

import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.UnifiedSyncState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * Hero state shown at the top of the redesigned Settings root.
 *
 * Drives the new MD3 Expressive Hero card. The card collapses to [NotConfigured] when the user has
 * not enabled any cloud provider — in that case it shows an onboarding call-to-action instead of a
 * stale sync summary.
 */
sealed interface SettingsHomeHeroState {
    data class Active(
        val activeProviders: ImmutableList<SyncBackendType>,
        val lastSuccessfulSyncMillis: Long?,
        val isCurrentlySyncing: Boolean,
    ) : SettingsHomeHeroState

    data object NotConfigured : SettingsHomeHeroState
}

internal fun computeSettingsHomeHeroState(
    gitEnabled: Boolean,
    gitLastSync: Long,
    gitSyncState: UnifiedSyncState,
    webDavEnabled: Boolean,
    webDavLastSync: Long,
    webDavSyncState: UnifiedSyncState,
    s3Enabled: Boolean,
    s3LastSync: Long,
    s3SyncState: UnifiedSyncState,
): SettingsHomeHeroState {
    val providers = mutableListOf<SyncBackendType>()
    var latestSync = 0L
    var anyRunning = false

    fun consume(enabled: Boolean, lastSync: Long, state: UnifiedSyncState, provider: SyncBackendType) {
        if (!enabled) return
        providers += provider
        if (lastSync > latestSync) latestSync = lastSync
        if (state is UnifiedSyncState.Running) anyRunning = true
    }

    consume(gitEnabled, gitLastSync, gitSyncState, SyncBackendType.GIT)
    consume(webDavEnabled, webDavLastSync, webDavSyncState, SyncBackendType.WEBDAV)
    consume(s3Enabled, s3LastSync, s3SyncState, SyncBackendType.S3)

    if (providers.isEmpty()) return SettingsHomeHeroState.NotConfigured

    return SettingsHomeHeroState.Active(
        activeProviders = providers.toImmutableList(),
        lastSuccessfulSyncMillis = latestSync.takeIf { it > 0 },
        isCurrentlySyncing = anyRunning,
    )
}
