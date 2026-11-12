package com.lomo.data.engine

import android.content.Intent

/** Missing optional extras select documented probe defaults; malformed/out-of-budget values fail. */
internal fun probeCount(raw: String?, default: Int, maximum: Int): Int {
    val value = if (raw == null) default else requireNotNull(raw.toIntOrNull()) { "Invalid probe count: $raw" }
    require(value in 1..maximum) { "Probe count must be in 1..$maximum" }
    return value
}

internal fun Intent.probeCount(key: String, default: Int, maximum: Int = MAX_PROBE_COUNT): Int =
    probeCount(getStringExtra(key), default, maximum)

private const val MAX_PROBE_COUNT = 10_000
