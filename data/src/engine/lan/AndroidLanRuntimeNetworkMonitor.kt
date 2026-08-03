package com.lomo.data.engine.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.lomo.data.share.AndroidLanShareNetworkPermissionGateway
import com.lomo.data.share.LanShareNetworkPermissionGateway
import com.lomo.data.share.resolveLanShareEligibleNetworkSnapshots

internal class AndroidLanRuntimeNetworkMonitor(
    context: Context,
    private val permissionGateway: LanShareNetworkPermissionGateway =
        AndroidLanShareNetworkPermissionGateway(context),
) : LanRuntimeNetworkMonitor {
    private val connectivity =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val observedNetworks = linkedSetOf<Network>()
    private var defaultCallbackRegistered = false
    private var localCallbackRegistered = false
    private var onChanged: ((LanPlatformNetworkSnapshot) -> Unit)? = null

    override fun snapshot(): LanPlatformNetworkSnapshot {
        val snapshots = resolveLanShareEligibleNetworkSnapshots(
            connectivityManager = connectivity,
            candidateNetworks = synchronized(observedNetworks) { observedNetworks.toSet() },
        )
        return LanPlatformNetworkSnapshot(
            permissionGranted = permissionGateway.hasRequiredPermissions(),
            candidates = snapshots.map { snapshot -> LanBindCandidate(snapshot.bindHost, 0u) },
        )
    }

    override fun start(onChanged: (LanPlatformNetworkSnapshot) -> Unit) {
        this.onChanged = onChanged
        val manager = connectivity ?: return
        if (!defaultCallbackRegistered) {
            manager.registerDefaultNetworkCallback(defaultCallback)
            defaultCallbackRegistered = true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && !localCallbackRegistered) {
            manager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK)
                    .build(),
                localCallback,
            )
            localCallbackRegistered = true
        }
        emit()
    }

    override fun stop() {
        val manager = connectivity ?: return
        if (defaultCallbackRegistered) {
            manager.unregisterNetworkCallback(defaultCallback)
            defaultCallbackRegistered = false
        }
        if (localCallbackRegistered) {
            manager.unregisterNetworkCallback(localCallback)
            localCallbackRegistered = false
        }
        onChanged = null
        synchronized(observedNetworks) { observedNetworks.clear() }
    }

    private fun emit() {
        onChanged?.invoke(snapshot())
    }

    private fun observe(network: Network) {
        synchronized(observedNetworks) { observedNetworks.add(network) }
        emit()
    }

    private fun forget(network: Network) {
        synchronized(observedNetworks) { observedNetworks.remove(network) }
        emit()
    }

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = observe(network)

        override fun onLost(network: Network) = forget(network)

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            emit()

        override fun onLinkPropertiesChanged(
            network: Network,
            linkProperties: android.net.LinkProperties,
        ) = emit()
    }

    private val localCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = observe(network)

        override fun onLost(network: Network) = forget(network)

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            emit()

        override fun onLinkPropertiesChanged(
            network: Network,
            linkProperties: android.net.LinkProperties,
        ) = emit()
    }
}
