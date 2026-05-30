package com.lomo.data.share

internal class LanShareActiveDiscoverySchedulePolicy(
    private val emptyScanInitialBackoffMs: Long = EMPTY_SCAN_INITIAL_BACKOFF_MS,
    private val emptyScanMaxBackoffMs: Long = EMPTY_SCAN_MAX_BACKOFF_MS,
    private val discoveredDeviceScanDelayMs: Long = DISCOVERED_DEVICE_SCAN_DELAY_MS,
) {
    private var nextDelayMs = PROMPT_SCAN_DELAY_MS
    private var emptyScanBackoffMs = emptyScanInitialBackoffMs

    fun delayBeforeNextScanMs(): Long = nextDelayMs

    fun recordScanResult(foundDeviceCount: Int) {
        if (foundDeviceCount > 0) {
            nextDelayMs = discoveredDeviceScanDelayMs
            emptyScanBackoffMs = emptyScanInitialBackoffMs
            return
        }

        nextDelayMs = emptyScanBackoffMs
        emptyScanBackoffMs = (emptyScanBackoffMs * BACKOFF_MULTIPLIER).coerceAtMost(emptyScanMaxBackoffMs)
    }

    private companion object {
        private const val PROMPT_SCAN_DELAY_MS = 0L
        private const val EMPTY_SCAN_INITIAL_BACKOFF_MS = 4_000L
        private const val EMPTY_SCAN_MAX_BACKOFF_MS = 60_000L
        private const val DISCOVERED_DEVICE_SCAN_DELAY_MS = 30_000L
        private const val BACKOFF_MULTIPLIER = 2
    }
}
