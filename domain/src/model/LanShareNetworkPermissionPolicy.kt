package com.lomo.domain.model

enum class LanShareNetworkPermissionRequirement {
    AccessLocalNetwork,
}

object LanShareNetworkPermissionPolicy {
    fun requiredRequirements(
        sdkInt: Int,
        isRequirementRecognized: (LanShareNetworkPermissionRequirement) -> Boolean,
    ): List<LanShareNetworkPermissionRequirement> =
        if (
            sdkInt >= ACCESS_LOCAL_NETWORK_SDK &&
            isRequirementRecognized(LanShareNetworkPermissionRequirement.AccessLocalNetwork)
        ) {
            listOf(LanShareNetworkPermissionRequirement.AccessLocalNetwork)
        } else {
            emptyList()
        }

    private const val ACCESS_LOCAL_NETWORK_SDK = 36
}
