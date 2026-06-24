package com.lomo.data.share

import android.net.Network
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.LanShareActiveProbeDiagnostics
import com.lomo.domain.model.LanShareActiveProbeState
import com.lomo.domain.model.LanShareDiscoveryDegradedReason
import com.lomo.domain.model.LanShareProbeRouteState
import com.lomo.domain.model.LanShareRouteSnapshot
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

internal data class LanShareActiveDiscoveryScanResult(
    val devices: List<DiscoveredDevice>,
    val snapshot: LanShareRouteSnapshot,
    val activeProbe: LanShareActiveProbeDiagnostics,
    val degradedReason: LanShareDiscoveryDegradedReason?,
) {
    companion object {
        fun routed(
            snapshot: LanShareActiveNetworkSnapshot,
            devices: List<DiscoveredDevice>,
            scanWindowOffset: Int = 0,
            probedTargetCount: Int = ACTIVE_DISCOVERY_TARGET_BUDGET,
        ): LanShareActiveDiscoveryScanResult =
            LanShareActiveDiscoveryScanResult(
                devices = devices,
                snapshot = snapshot.toRouteSnapshot(LanShareProbeRouteState.BoundNetwork),
                activeProbe =
                    LanShareActiveProbeDiagnostics(
                        state = LanShareActiveProbeState.Scanning,
                        snapshotCount = 1,
                        routeCapableSnapshotCount = 1,
                        degradedSnapshotCount = 0,
                        targetBudget = ACTIVE_DISCOVERY_TARGET_BUDGET,
                        scanWindowOffset = scanWindowOffset,
                        probedTargetCount = probedTargetCount,
                        foundDeviceCount = devices.size,
                    ),
                degradedReason = null,
            )

        fun degradedNoRoute(snapshot: LanShareActiveNetworkSnapshot): LanShareActiveDiscoveryScanResult =
            LanShareActiveDiscoveryScanResult(
                devices = emptyList(),
                snapshot = snapshot.toRouteSnapshot(LanShareProbeRouteState.DegradedNoNetwork),
                activeProbe =
                    LanShareActiveProbeDiagnostics(
                        state = LanShareActiveProbeState.DegradedNoRoute,
                        snapshotCount = 1,
                        routeCapableSnapshotCount = 0,
                        degradedSnapshotCount = 1,
                        targetBudget = ACTIVE_DISCOVERY_TARGET_BUDGET,
                        probedTargetCount = 0,
                        foundDeviceCount = 0,
                    ),
                degradedReason = LanShareDiscoveryDegradedReason.FallbackSnapshotWithoutNetwork,
            )
    }
}

internal interface LanShareActiveDiscoveryScanner {
    suspend fun scan(snapshot: LanShareActiveNetworkSnapshot): LanShareActiveDiscoveryScanResult
}

internal fun buildLanShareActiveDiscoveryTargets(
    bindHost: String,
    network: Network? = null,
    scanWindowOffset: Int = 0,
): List<LanShareActiveDiscoveryTarget> {
    // behavior-contract: silent-result-ok: unresolvable bindHost → emptyList (no probe targets)
    val localAddress = runCatching { InetAddress.getByName(bindHost) }.getOrNull() as? Inet4Address
        ?: return emptyList()
    if (!localAddress.isSiteLocalAddress) return emptyList()

    val octets = localAddress.address.map { byte -> byte.toInt() and IPV4_UNSIGNED_BYTE_MASK }
    if (octets.size != IPV4_OCTET_COUNT) return emptyList()
    val localHostSuffix = octets[3]
    val prefix = "${octets[0]}.${octets[1]}.${octets[2]}."
    return prioritizedLanShareHostSuffixes(
        localHostSuffix = localHostSuffix,
        scanWindowOffset = scanWindowOffset,
    )
        .asSequence()
        .map { hostSuffix -> "$prefix$hostSuffix" }
        .map { host -> LanShareActiveDiscoveryTarget(host, LAN_SHARE_DISCOVERY_PORT, network) }
        .toList()
}

private fun prioritizedLanShareHostSuffixes(
    localHostSuffix: Int,
    scanWindowOffset: Int,
): List<Int> {
    val prioritySuffixes =
        listOf(
            IPV4_USABLE_HOST_MIN,
            (localHostSuffix + 1).coerceAtMost(IPV4_USABLE_HOST_MAX),
            (localHostSuffix - 1).coerceAtLeast(IPV4_USABLE_HOST_MIN),
            IPV4_USABLE_HOST_MAX,
        ).filterNot { hostSuffix -> hostSuffix == localHostSuffix }
            .distinct()
    val prioritySet = prioritySuffixes.toSet()
    val nonPrioritySuffixes =
        (IPV4_USABLE_HOST_MIN..IPV4_USABLE_HOST_MAX)
            .filterNot { hostSuffix -> hostSuffix == localHostSuffix || hostSuffix in prioritySet }
    val nonPriorityBudget = (ACTIVE_DISCOVERY_TARGET_BUDGET - prioritySuffixes.size).coerceAtLeast(0)
    val normalizedWindowOffset =
        if (nonPrioritySuffixes.isEmpty()) {
            0
        } else {
            scanWindowOffset.coerceAtLeast(0) % nonPrioritySuffixes.size
        }
    val rotatedNonPrioritySuffixes =
        nonPrioritySuffixes.drop(normalizedWindowOffset) + nonPrioritySuffixes.take(normalizedWindowOffset)
    return (prioritySuffixes + rotatedNonPrioritySuffixes.take(nonPriorityBudget))
        .take(ACTIVE_DISCOVERY_TARGET_BUDGET)
}

internal class LanShareActiveDiscoveryClient(
    private val probeDevice: suspend (LanShareActiveDiscoveryTarget) -> DiscoveredDevice? =
        ::probeLanShareDevice,
) : LanShareActiveDiscoveryScanner {
    private val scanWindowLock = Any()
    private val nextScanWindowOffsets = mutableMapOf<String, Int>()

    override suspend fun scan(snapshot: LanShareActiveNetworkSnapshot): LanShareActiveDiscoveryScanResult {
        if (snapshot.network == null) {
            Timber
                .tag(TAG)
                .d(
                    "LAN active probe degraded for %s: missing Android Network route",
                    snapshot.networkKey,
                )
            return LanShareActiveDiscoveryScanResult.degradedNoRoute(snapshot)
        }
        val scanWindowOffset = claimScanWindowOffset(snapshot)
        val targets =
            buildLanShareActiveDiscoveryTargets(
                bindHost = snapshot.bindHost,
                network = snapshot.network,
                scanWindowOffset = scanWindowOffset,
            )
        if (targets.isEmpty()) {
            return LanShareActiveDiscoveryScanResult.routed(
                snapshot = snapshot,
                devices = emptyList(),
                scanWindowOffset = scanWindowOffset,
                probedTargetCount = 0,
            )
        }
        val devices =
            targets
            .chunked(ACTIVE_DISCOVERY_CONCURRENCY)
            .flatMap { chunk ->
                coroutineScope {
                    chunk
                        .map { target ->
                            async(Dispatchers.IO) {
                                // behavior-contract: silent-result-ok:
                                // unreachable probe target means no peer at this address.
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
        return LanShareActiveDiscoveryScanResult.routed(
            snapshot = snapshot,
            devices = devices,
            scanWindowOffset = scanWindowOffset,
            probedTargetCount = targets.size,
        )
    }

    private fun claimScanWindowOffset(snapshot: LanShareActiveNetworkSnapshot): Int =
        synchronized(scanWindowLock) {
            val key = "${snapshot.networkKey}:${snapshot.bindHost}"
            val currentOffset = nextScanWindowOffsets[key] ?: 0
            nextScanWindowOffsets[key] = currentOffset + ACTIVE_DISCOVERY_NON_PRIORITY_TARGET_BUDGET
            currentOffset
        }
}

internal suspend fun probeLanShareDevice(target: LanShareActiveDiscoveryTarget): DiscoveredDevice? =
    withContext(Dispatchers.IO) {
        val network = target.network ?: return@withContext null
        val url = URI("http://${target.host}:${target.port}/share/ping").toURL()
        val connection =
            (network.openConnection(url) as HttpURLConnection)
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

internal fun LanShareActiveNetworkSnapshot.toRouteSnapshot(
    routeState: LanShareProbeRouteState =
        if (network == null) {
            LanShareProbeRouteState.DegradedNoNetwork
        } else {
            LanShareProbeRouteState.BoundNetwork
        },
): LanShareRouteSnapshot =
    LanShareRouteSnapshot(
        networkKey = networkKey,
        bindHost = bindHost,
        routeState = routeState,
    )

private const val TAG = "LanShareActiveDiscovery"
private const val ACTIVE_DISCOVERY_CONCURRENCY = 32
internal const val ACTIVE_DISCOVERY_TARGET_BUDGET = 64
internal const val ACTIVE_DISCOVERY_NON_PRIORITY_TARGET_BUDGET = ACTIVE_DISCOVERY_TARGET_BUDGET - 4
private const val ACTIVE_DISCOVERY_TIMEOUT_MS = 250
private const val IPV4_OCTET_COUNT = 4
private const val IPV4_USABLE_HOST_MIN = 1
private const val IPV4_USABLE_HOST_MAX = 254
private const val IPV4_UNSIGNED_BYTE_MASK = 0xFF
