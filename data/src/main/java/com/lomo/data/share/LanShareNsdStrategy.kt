package com.lomo.data.share

import android.net.Network

internal const val LAN_SHARE_GLOBAL_NSD_NETWORK_KEY = "global-nsd"

internal data class LanShareNsdStrategy(
    val networkKey: String,
    val targetNetwork: Network?,
)

internal fun buildLanShareNsdStrategies(
    snapshots: List<LanShareActiveNetworkSnapshot>,
): List<LanShareNsdStrategy> =
    (
        listOf(
            LanShareNsdStrategy(
                networkKey = LAN_SHARE_GLOBAL_NSD_NETWORK_KEY,
                targetNetwork = null,
            ),
        ) +
            snapshots.mapNotNull { snapshot ->
                snapshot.network?.let { network ->
                    LanShareNsdStrategy(
                        networkKey = snapshot.networkKey,
                        targetNetwork = network,
                    )
                }
            }
    ).distinctBy(LanShareNsdStrategy::networkKey)
