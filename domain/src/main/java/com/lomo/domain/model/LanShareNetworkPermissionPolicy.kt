package com.lomo.domain.model

enum class LanShareNetworkPermissionRequirement {
    NearbyWifiDevices,
    AccessLocalNetwork,
}

object LanShareNetworkPermissionPolicy {
    fun requiredRequirements(
        sdkInt: Int,
        isRequirementRecognized: (LanShareNetworkPermissionRequirement) -> Boolean,
    ): List<LanShareNetworkPermissionRequirement> =
        buildList {
            if (sdkInt >= NEARBY_WIFI_DEVICES_SDK) {
                add(LanShareNetworkPermissionRequirement.NearbyWifiDevices)
            }
            if (
                sdkInt >= ACCESS_LOCAL_NETWORK_SDK &&
                isRequirementRecognized(LanShareNetworkPermissionRequirement.AccessLocalNetwork)
            ) {
                add(LanShareNetworkPermissionRequirement.AccessLocalNetwork)
            }
        }

    private const val NEARBY_WIFI_DEVICES_SDK = 33
    private const val ACCESS_LOCAL_NETWORK_SDK = 36
}
