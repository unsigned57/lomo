package com.lomo.data.share

import android.net.Network

internal class LanShareObservedNetworkRegistry {
    private val lock = Any()
    private val observedNetworks = mutableSetOf<Network>()

    fun remember(network: Network) {
        synchronized(lock) {
            observedNetworks.add(network)
        }
    }

    fun forget(network: Network) {
        synchronized(lock) {
            observedNetworks.remove(network)
        }
    }

    fun snapshot(): Set<Network> =
        synchronized(lock) {
            observedNetworks.toSet()
        }
}
