package com.lomo.app.feature.lanshare

object LanSharePairingDialogTriggerPolicy {
    fun shouldShowOnPairingRequiredEvent(eventToken: Int): Boolean = eventToken > 0

    fun shouldShowOnE2eEnabled(
        enabled: Boolean,
        pairingConfigured: Boolean,
    ): Boolean = enabled && !pairingConfigured
}
