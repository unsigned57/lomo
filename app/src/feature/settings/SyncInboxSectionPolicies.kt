package com.lomo.app.feature.settings

internal data class SyncInboxSectionPolicy(
    val showSectionHeader: Boolean,
    val headerInteractive: Boolean,
    val showDirectoryPreference: Boolean,
)

internal object SyncInboxSectionPolicies {
    fun resolve(enabled: Boolean): SyncInboxSectionPolicy =
        SyncInboxSectionPolicy(
            showSectionHeader = true,
            headerInteractive = true,
            showDirectoryPreference = enabled,
        )
}
