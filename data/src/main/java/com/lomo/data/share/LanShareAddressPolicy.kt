package com.lomo.data.share

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

internal fun isLanSharePrivateHost(host: String): Boolean {
    val normalizedHost =
        host
            .removePrefix("[")
            .removeSuffix("]")
            .substringBefore('%')
            .trim()
    if (normalizedHost.isBlank()) return false
    if (normalizedHost == "localhost") return true
    val address = runCatching { InetAddress.getByName(normalizedHost) }.getOrNull() ?: return false
    return address.isLanSharePrivateAddress()
}

internal fun selectLanShareBindHostAddress(addresses: List<InetAddress>): String? =
    addresses.firstNotNullOfOrNull { address ->
        when {
            address is Inet4Address && address.isLanSharePrivateAddress() -> address.hostAddress
            else -> null
        }
    } ?: addresses.firstNotNullOfOrNull { address ->
        when {
            address is Inet6Address && address.isLanSharePrivateAddress() -> address.hostAddress?.substringBefore('%')
            else -> null
        }
    }

internal data class LanShareActiveNetworkSnapshot(
    val networkKey: String,
    val bindHost: String,
    val network: Network? = null,
)

internal data class LanShareNetworkProbe(
    val networkKey: String,
    val bindHost: String?,
    val isActiveNetwork: Boolean,
    val hasWifiTransport: Boolean,
    val hasEthernetTransport: Boolean,
    val hasLocalNetworkCapability: Boolean,
    val network: Network? = null,
)

internal data class LanShareInterfaceProbe(
    val name: String,
    val addresses: List<InetAddress>,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val isVirtual: Boolean,
    val isPointToPoint: Boolean,
)

internal fun resolveLanShareBindHost(connectivityManager: ConnectivityManager?): String? {
    return resolveLanShareActiveNetworkSnapshot(connectivityManager)?.bindHost
}

internal fun resolveLanShareActiveNetworkSnapshot(
    connectivityManager: ConnectivityManager?,
    candidateNetworks: Set<Network> = emptySet(),
): LanShareActiveNetworkSnapshot? =
    resolveLanShareEligibleNetworkSnapshots(connectivityManager, candidateNetworks).firstOrNull()

internal fun selectLanShareActiveNetworkSnapshot(
    probes: List<LanShareNetworkProbe>,
): LanShareActiveNetworkSnapshot? = selectLanShareEligibleNetworkSnapshots(probes).firstOrNull()

internal fun selectLanShareInterfaceFallbackSnapshot(
    probes: List<LanShareInterfaceProbe>,
): LanShareActiveNetworkSnapshot? = selectLanShareEligibleInterfaceFallbackSnapshots(probes).firstOrNull()

internal fun ConnectivityManager.toLanShareNetworkProbes(candidateNetworks: Set<Network>): List<LanShareNetworkProbe> {
    val activeNetwork = activeNetwork
    val orderedNetworks =
        buildList {
            activeNetwork?.let(::add)
            candidateNetworks
                .filterNot { network -> network == activeNetwork }
                .forEach(::add)
            reflectedLanShareNetworks()
                .filterNot { network -> network == activeNetwork || network in candidateNetworks }
                .forEach(::add)
        }
    return orderedNetworks.mapNotNull { network ->
        val capabilities = getNetworkCapabilities(network) ?: return@mapNotNull null
        val linkProperties = getLinkProperties(network)
        LanShareNetworkProbe(
            networkKey = network.toString(),
            bindHost = linkProperties?.linkAddresses?.map { it.address }?.let(::selectLanShareBindHostAddress),
            isActiveNetwork = network == activeNetwork,
            hasWifiTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            hasEthernetTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            hasLocalNetworkCapability = capabilities.hasLanShareLocalNetworkCapability(),
            network = network,
        )
    }
}

private fun ConnectivityManager.reflectedLanShareNetworks(): List<Network> =
    runCatching {
        val networks =
            ConnectivityManager::class.java
                .getMethod("getAllNetworks")
                .invoke(this) as? Array<*>
        networks?.filterIsInstance<Network>().orEmpty()
    }.getOrDefault(emptyList())

internal fun enumerateLanShareInterfaceProbes(): List<LanShareInterfaceProbe> =
    runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching emptyList()
        Collections.list(interfaces).map { networkInterface ->
            LanShareInterfaceProbe(
                name = networkInterface.name,
                addresses = Collections.list(networkInterface.inetAddresses),
                isUp = runCatching { networkInterface.isUp }.getOrDefault(false),
                isLoopback = runCatching { networkInterface.isLoopback }.getOrDefault(false),
                isVirtual = runCatching { networkInterface.isVirtual }.getOrDefault(false),
                isPointToPoint = runCatching { networkInterface.isPointToPoint }.getOrDefault(false),
            )
        }
    }.getOrDefault(emptyList())
