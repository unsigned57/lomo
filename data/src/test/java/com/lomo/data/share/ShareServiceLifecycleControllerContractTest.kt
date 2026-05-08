package com.lomo.data.share

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: ShareServiceLifecycleController LAN-network lifecycle contract.
 * - Behavior focus: LAN share services must restart when the usable LAN network changes, including
 *   Android local-network/hotspot links that may not be ConnectivityManager.activeNetwork, and
 *   refreshed NSD registrations must keep the selected LAN network scope. Discovery retry must
 *   also have a non-NSD active scan path for hotspots where multicast discovery is unavailable.
 * - Observable outcomes: source-level adoption of default-network and local-network callbacks.
 * - Red phase: Fails before the fix because lifecycle only registers the default network
 *   callback, so hotspot local-network availability can change without restarting NSD.
 * - Excludes: live ConnectivityManager callback delivery, NSD packets, and socket I/O.
 */
class ShareServiceLifecycleControllerContractTest {
    @Test
    fun `lifecycle controller monitors active network changes for LAN share restart`() {
        val source = File("src/main/java/com/lomo/data/share/ShareServiceLifecycleController.kt").readText()

        assertTrue(source.contains("ConnectivityManager.NetworkCallback"))
        assertTrue(source.contains("registerDefaultNetworkCallback"))
        assertTrue(source.contains("networkRestartDebouncer.trigger()"))
    }

    @Test
    fun `lifecycle controller monitors android local network changes for hotspot lan share restart`() {
        val source = File("src/main/java/com/lomo/data/share/ShareServiceLifecycleController.kt").readText()

        assertTrue(source.contains("localNetworkCallbackRegistered"))
        assertTrue(source.contains("NetworkRequest.Builder()"))
        assertTrue(source.contains("NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK"))
        assertTrue(source.contains("registerNetworkCallback("))
    }

    @Test
    fun `lifecycle controller refresh keeps selected lan network scope`() {
        val source = File("src/main/java/com/lomo/data/share/ShareServiceLifecycleController.kt").readText()

        assertTrue(source.contains("val targetNetwork = activeNetworkSnapshot?.network"))
        assertTrue(source.contains("targetNetwork = targetNetwork"))
    }

    @Test
    fun `lifecycle controller runs active lan scan when discovery is requested again`() {
        val source = File("src/main/java/com/lomo/data/share/ShareServiceLifecycleController.kt").readText()

        assertTrue(source.contains("LanShareActiveDiscoveryClient("))
        assertTrue(source.contains("startActiveDiscoveryScan("))
        assertTrue(source.contains("nsdService.mergeDiscoveredDevices"))
    }
}
