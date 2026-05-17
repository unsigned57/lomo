package com.lomo.data.share

import android.net.ConnectivityManager
import android.net.Network

internal fun resolveLanShareEligibleNetworkSnapshots(
    connectivityManager: ConnectivityManager?,
    candidateNetworks: Set<Network> = emptySet(),
): List<LanShareActiveNetworkSnapshot> {
    val networkSnapshots =
        connectivityManager
            ?.toLanShareNetworkProbes(candidateNetworks)
            ?.let(::selectLanShareEligibleNetworkSnapshots)
            .orEmpty()
    val interfaceSnapshots = selectLanShareEligibleInterfaceFallbackSnapshots(enumerateLanShareInterfaceProbes())
    return mergeLanShareEligibleSnapshots(networkSnapshots, interfaceSnapshots)
}

internal fun selectLanShareEligibleNetworkSnapshots(
    probes: List<LanShareNetworkProbe>,
): List<LanShareActiveNetworkSnapshot> =
    probes
        .asSequence()
        .mapNotNull { probe ->
            val priority = probe.lanSharePriority() ?: return@mapNotNull null
            val bindHost = probe.bindHost ?: return@mapNotNull null
            priority to
                LanShareActiveNetworkSnapshot(
                    networkKey = probe.networkKey,
                    bindHost = bindHost,
                    network = probe.network,
                )
        }.sortedWith(
            compareBy<Pair<Int, LanShareActiveNetworkSnapshot>> { it.first }
                .thenByDescending { (_, snapshot) ->
                    probes.firstOrNull { probe -> probe.networkKey == snapshot.networkKey }?.isActiveNetwork == true
                },
        ).map { it.second }
        .toList()

internal fun selectLanShareEligibleInterfaceFallbackSnapshots(
    probes: List<LanShareInterfaceProbe>,
): List<LanShareActiveNetworkSnapshot> =
    probes
        .asSequence()
        .mapNotNull { probe ->
            val priority = probe.lanShareInterfacePriority() ?: return@mapNotNull null
            val bindHost = selectLanShareBindHostAddress(probe.addresses) ?: return@mapNotNull null
            priority to
                LanShareActiveNetworkSnapshot(
                    networkKey = "if:${probe.name}",
                    bindHost = bindHost,
                )
        }.sortedWith(
            compareBy<Pair<Int, LanShareActiveNetworkSnapshot>> { it.first }
                .thenBy { (_, snapshot) -> snapshot.networkKey },
        ).map { it.second }
        .toList()

internal fun mergeLanShareEligibleSnapshots(
    networkSnapshots: List<LanShareActiveNetworkSnapshot>,
    interfaceSnapshots: List<LanShareActiveNetworkSnapshot>,
): List<LanShareActiveNetworkSnapshot> {
    val claimedBindHosts = networkSnapshots.map { it.bindHost }.toSet()
    return networkSnapshots +
        interfaceSnapshots.filterNot { fallback -> fallback.bindHost in claimedBindHosts }
}
