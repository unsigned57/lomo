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

/** One provider's enablement and last-known sync facts for the Settings hero card. */
internal data class HomeHeroProviderFacts(
    val enabled: Boolean,
    val lastSync: Long,
    val syncState: UnifiedSyncState,
)

internal fun computeSettingsHomeHeroState(
    git: HomeHeroProviderFacts,
    webDav: HomeHeroProviderFacts,
    s3: HomeHeroProviderFacts,
): SettingsHomeHeroState {
    val providers = mutableListOf<SyncBackendType>()
    var latestSync = 0L
    var anyRunning = false

    fun consume(facts: HomeHeroProviderFacts, provider: SyncBackendType) {
        if (!facts.enabled) return
        providers += provider
        if (facts.lastSync > latestSync) latestSync = facts.lastSync
        if (facts.syncState is UnifiedSyncState.Running) anyRunning = true
    }

    consume(git, SyncBackendType.GIT)
    consume(webDav, SyncBackendType.WEBDAV)
    consume(s3, SyncBackendType.S3)

    if (providers.isEmpty()) return SettingsHomeHeroState.NotConfigured

    return SettingsHomeHeroState.Active(
        activeProviders = providers.toImmutableList(),
        lastSuccessfulSyncMillis = latestSync.takeIf { it > 0 },
        isCurrentlySyncing = anyRunning,
    )
}
