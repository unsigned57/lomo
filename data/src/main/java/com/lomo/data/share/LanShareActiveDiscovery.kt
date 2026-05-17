package com.lomo.data.share

import android.net.Network
import com.lomo.domain.model.DiscoveredDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException

internal const val LAN_SHARE_DISCOVERY_PORT = 42137
internal const val LAN_SHARE_PING_RESPONSE_PREFIX = "lomo-share\t"

internal data class LanShareActiveDiscoveryTarget(
    val host: String,
    val port: Int,
    val network: Network? = null,
)

internal interface LanShareActiveDiscoveryScanner {
    suspend fun scan(snapshot: LanShareActiveNetworkSnapshot): List<DiscoveredDevice>
}

internal fun buildLanShareActiveDiscoveryTargets(
    bindHost: String,
    network: Network? = null,
): List<LanShareActiveDiscoveryTarget> {
    val localAddress = runCatching { InetAddress.getByName(bindHost) }.getOrNull() as? Inet4Address
        ?: return emptyList()
    if (!localAddress.isSiteLocalAddress) return emptyList()

    val octets = localAddress.address.map { byte -> byte.toInt() and IPV4_UNSIGNED_BYTE_MASK }
    if (octets.size != IPV4_OCTET_COUNT) return emptyList()
    val prefix = "${octets[0]}.${octets[1]}.${octets[2]}."
    return (IPV4_USABLE_HOST_MIN..IPV4_USABLE_HOST_MAX)
        .asSequence()
        .map { hostSuffix -> "$prefix$hostSuffix" }
        .filterNot { host -> host == bindHost }
        .map { host -> LanShareActiveDiscoveryTarget(host, LAN_SHARE_DISCOVERY_PORT, network) }
        .toList()
}

internal class LanShareActiveDiscoveryClient(
    private val probeDevice: suspend (LanShareActiveDiscoveryTarget) -> DiscoveredDevice? =
        ::probeLanShareDevice,
) : LanShareActiveDiscoveryScanner {
    override suspend fun scan(snapshot: LanShareActiveNetworkSnapshot): List<DiscoveredDevice> {
        val targets =
            buildLanShareActiveDiscoveryTargets(
                bindHost = snapshot.bindHost,
                network = snapshot.network,
            )
        if (targets.isEmpty()) return emptyList()
        return targets
            .chunked(ACTIVE_DISCOVERY_CONCURRENCY)
            .flatMap { chunk ->
                coroutineScope {
                    chunk
                        .map { target ->
                            async(Dispatchers.IO) {
                                runCatching { probeDevice(target) }
                                    .onFailure { error ->
                                        if (error is CancellationException) throw error
                                        Timber.tag(TAG).d(error, "LAN active probe failed for ${target.host}")
                                    }.getOrNull()
                            }
                        }.awaitAll()
                        .filterNotNull()
                }
            }.distinctBy { device -> device.endpointKey() }
    }
}

private suspend fun probeLanShareDevice(target: LanShareActiveDiscoveryTarget): DiscoveredDevice? =
    withContext(Dispatchers.IO) {
        val url = URI("http://${target.host}:${target.port}/share/ping").toURL()
        val connection =
            ((target.network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection)
                .apply {
                    requestMethod = "GET"
                    connectTimeout = ACTIVE_DISCOVERY_TIMEOUT_MS
                    readTimeout = ACTIVE_DISCOVERY_TIMEOUT_MS
                }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val body = connection.inputStream.bufferedReader().use { reader -> reader.readText() }
            mapLanSharePingResponse(target, body)
        } finally {
            connection.disconnect()
        }
    }

internal fun mapLanSharePingResponse(
    target: LanShareActiveDiscoveryTarget,
    body: String,
): DiscoveredDevice? {
    val deviceName =
        body
            .takeIf { it.startsWith(LAN_SHARE_PING_RESPONSE_PREFIX) }
            ?.removePrefix(LAN_SHARE_PING_RESPONSE_PREFIX)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null
    return DiscoveredDevice(
        name = deviceName,
        host = target.host,
        port = target.port,
    )
}

private fun DiscoveredDevice.endpointKey(): String = "$host:$port"

private const val TAG = "LanShareActiveDiscovery"
private const val ACTIVE_DISCOVERY_CONCURRENCY = 32
private const val ACTIVE_DISCOVERY_TIMEOUT_MS = 250
private const val IPV4_OCTET_COUNT = 4
private const val IPV4_USABLE_HOST_MIN = 1
private const val IPV4_USABLE_HOST_MAX = 254
private const val IPV4_UNSIGNED_BYTE_MASK = 0xFF
