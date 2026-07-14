package com.lomo.app

internal enum class CapabilityGateId {
    Notifications,
    ExactAlarm,
    InstallUnknownApps,
    RecordAudio,
    Location,
    LocalNetwork,
    SafWorkspaceAccess,
    SafMediaAccess,
}

internal enum class CapabilityBlockLevel {
    FirstScreenBlocked,
    FeatureBlocked,
    OptionalEnhancement,
}

internal enum class CapabilitySensitivity {
    UserSelectedFiles,
    UserSelectedMedia,
    Microphone,
    Location,
    NearbyDevicesOrLocalNetwork,
}

internal enum class CapabilitySettingsEntry {
    ExactAlarmSettings,
    UnknownAppSourcesSettings,
}

internal sealed interface CapabilityRecoveryAction {
    data object RequestRuntimePermissions : CapabilityRecoveryAction
    data object OpenAppSettings : CapabilityRecoveryAction
    data class OpenSettings(val settingsEntry: CapabilitySettingsEntry) : CapabilityRecoveryAction
    data object SelectSafTree : CapabilityRecoveryAction
    data object SelectSafDocument : CapabilityRecoveryAction
}

internal enum class CapabilityRecoveryDecision {
    Denied,
    PermanentlyDenied,
}

internal enum class CapabilityGrantAggregation {
    All,
    Any,
    Custom,
}

internal object CapabilityPermissionNames {
    const val PostNotifications = "android.permission.POST_NOTIFICATIONS"
    const val RecordAudio = "android.permission.RECORD_AUDIO"
    const val AccessFineLocation = "android.permission.ACCESS_FINE_LOCATION"
    const val AccessCoarseLocation = "android.permission.ACCESS_COARSE_LOCATION"
    const val AccessLocalNetwork = "android.permission.ACCESS_LOCAL_NETWORK"
}

internal data class CapabilityRuntimePermission(
    val name: String,
    val minSdk: Int? = null,
    val requiresSystemRecognition: Boolean = false,
) {
    fun isRequired(
        sdkInt: Int,
        isPermissionRecognized: (String) -> Boolean,
    ): Boolean =
        (minSdk == null || sdkInt >= minSdk) &&
            (!requiresSystemRecognition || isPermissionRecognized(name))
}

internal data class CapabilityRuntimePermissionPlan(
    val permissions: List<CapabilityRuntimePermission>,
    val grantAggregation: CapabilityGrantAggregation,
) {
    fun requiredPermissions(
        sdkInt: Int,
        isPermissionRecognized: (String) -> Boolean = { true },
    ): List<String> =
        permissions
            .filter { permission -> permission.isRequired(sdkInt, isPermissionRecognized) }
            .map { permission -> permission.name }

    fun isGrantSatisfied(
        requiredPermissions: List<String>,
        permissionResults: Map<String, Boolean>,
        hasCurrentPermissions: Boolean,
    ): Boolean =
        requiredPermissions.isEmpty() ||
            hasCurrentPermissions ||
            when (grantAggregation) {
                CapabilityGrantAggregation.All ->
                    requiredPermissions.all { permission -> permissionResults[permission] == true }
                CapabilityGrantAggregation.Any ->
                    requiredPermissions.any { permission -> permissionResults[permission] == true }
                CapabilityGrantAggregation.Custom ->
                    error("Custom grant aggregation requires a capability-specific evaluator.")
            }
}

internal data class CapabilityRecoveryPlan(
    val primaryRequestAction: CapabilityRecoveryAction,
    val runtimePermissionPlan: CapabilityRuntimePermissionPlan? = null,
    val deniedFallbackAction: CapabilityRecoveryAction? = null,
    val permanentlyDeniedFallbackAction: CapabilityRecoveryAction? = null,
    val canRetryAfterRecovery: Boolean,
) {
    fun fallbackAction(decision: CapabilityRecoveryDecision): CapabilityRecoveryAction? =
        when (decision) {
            CapabilityRecoveryDecision.Denied -> deniedFallbackAction
            CapabilityRecoveryDecision.PermanentlyDenied -> permanentlyDeniedFallbackAction
        }
}

internal data class CapabilityGate(
    val id: CapabilityGateId,
    val blockLevel: CapabilityBlockLevel,
    val sensitivityCategories: Set<CapabilitySensitivity>,
    val recoveryPlan: CapabilityRecoveryPlan,
    val blocksFirstScreen: Boolean,
)

internal object CapabilityGatePolicies {
    val all: List<CapabilityGate> =
        listOf(
            CapabilityGate(
                id = CapabilityGateId.Notifications,
                blockLevel = CapabilityBlockLevel.FeatureBlocked,
                sensitivityCategories = emptySet(),
                recoveryPlan = runtimeRecoveryPlan(
                    permissions = listOf(
                        CapabilityRuntimePermission(
                            name = CapabilityPermissionNames.PostNotifications,
                            minSdk = 33,
                        ),
                    ),
                    grantAggregation = CapabilityGrantAggregation.All,
                ),
                blocksFirstScreen = false,
            ),
            CapabilityGate(
                id = CapabilityGateId.ExactAlarm,
                blockLevel = CapabilityBlockLevel.FeatureBlocked,
                sensitivityCategories = emptySet(),
                recoveryPlan = systemSettingsRecoveryPlan(CapabilitySettingsEntry.ExactAlarmSettings),
                blocksFirstScreen = false,
            ),
            CapabilityGate(
                id = CapabilityGateId.InstallUnknownApps,
                blockLevel = CapabilityBlockLevel.FeatureBlocked,
                sensitivityCategories = emptySet(),
                recoveryPlan = systemSettingsRecoveryPlan(CapabilitySettingsEntry.UnknownAppSourcesSettings),
                blocksFirstScreen = false,
            ),
            CapabilityGate(
                id = CapabilityGateId.RecordAudio,
                blockLevel = CapabilityBlockLevel.OptionalEnhancement,
                sensitivityCategories = setOf(CapabilitySensitivity.Microphone),
                recoveryPlan = runtimeRecoveryPlan(
                    permissions = listOf(CapabilityRuntimePermission(CapabilityPermissionNames.RecordAudio)),
                    grantAggregation = CapabilityGrantAggregation.All,
                ),
                blocksFirstScreen = false,
            ),
            CapabilityGate(
                id = CapabilityGateId.Location,
                blockLevel = CapabilityBlockLevel.OptionalEnhancement,
                sensitivityCategories = setOf(CapabilitySensitivity.Location),
                recoveryPlan = runtimeRecoveryPlan(
                    permissions = listOf(
                        CapabilityRuntimePermission(CapabilityPermissionNames.AccessFineLocation),
                        CapabilityRuntimePermission(CapabilityPermissionNames.AccessCoarseLocation),
                    ),
                    grantAggregation = CapabilityGrantAggregation.Any,
                ),
                blocksFirstScreen = false,
            ),
            CapabilityGate(
                id = CapabilityGateId.LocalNetwork,
                blockLevel = CapabilityBlockLevel.OptionalEnhancement,
                sensitivityCategories = setOf(CapabilitySensitivity.NearbyDevicesOrLocalNetwork),
                recoveryPlan = runtimeRecoveryPlan(
                    permissions = listOf(
                        CapabilityRuntimePermission(
                            name = CapabilityPermissionNames.AccessLocalNetwork,
                            minSdk = ANDROID_BAKLAVA_API_LEVEL,
                            requiresSystemRecognition = true,
                        ),
                    ),
                    grantAggregation = CapabilityGrantAggregation.All,
                ),
                blocksFirstScreen = false,
            ),
            CapabilityGate(
                id = CapabilityGateId.SafWorkspaceAccess,
                blockLevel = CapabilityBlockLevel.FirstScreenBlocked,
                sensitivityCategories = setOf(CapabilitySensitivity.UserSelectedFiles),
                recoveryPlan = CapabilityRecoveryPlan(
                    primaryRequestAction = CapabilityRecoveryAction.SelectSafTree,
                    canRetryAfterRecovery = true,
                ),
                blocksFirstScreen = true,
            ),
            CapabilityGate(
                id = CapabilityGateId.SafMediaAccess,
                blockLevel = CapabilityBlockLevel.FeatureBlocked,
                sensitivityCategories = setOf(CapabilitySensitivity.UserSelectedMedia),
                recoveryPlan = CapabilityRecoveryPlan(
                    primaryRequestAction = CapabilityRecoveryAction.SelectSafDocument,
                    canRetryAfterRecovery = true,
                ),
                blocksFirstScreen = false,
            ),
        )

    private val byId: Map<CapabilityGateId, CapabilityGate> =
        all.associateBy(CapabilityGate::id).also { indexedPolicies ->
            check(indexedPolicies.size == CapabilityGateId.entries.size) {
                "Capability gate catalog must define exactly one policy for every capability id."
            }
        }

    fun requirePolicy(id: CapabilityGateId): CapabilityGate =
        requireNotNull(byId[id]) { "Missing capability gate policy for $id." }

    fun firstScreenBlockers(): List<CapabilityGate> =
        all.filter { gate -> gate.blocksFirstScreen }
}

internal const val ANDROID_BAKLAVA_API_LEVEL = 36

private fun runtimeRecoveryPlan(
    permissions: List<CapabilityRuntimePermission>,
    grantAggregation: CapabilityGrantAggregation,
): CapabilityRecoveryPlan =
    CapabilityRecoveryPlan(
        primaryRequestAction = CapabilityRecoveryAction.RequestRuntimePermissions,
        runtimePermissionPlan = CapabilityRuntimePermissionPlan(
            permissions = permissions,
            grantAggregation = grantAggregation,
        ),
        deniedFallbackAction = CapabilityRecoveryAction.OpenAppSettings,
        permanentlyDeniedFallbackAction = CapabilityRecoveryAction.OpenAppSettings,
        canRetryAfterRecovery = true,
    )

private fun systemSettingsRecoveryPlan(settingsEntry: CapabilitySettingsEntry): CapabilityRecoveryPlan =
    CapabilityRecoveryPlan(
        primaryRequestAction = CapabilityRecoveryAction.OpenSettings(settingsEntry),
        canRetryAfterRecovery = true,
    )
