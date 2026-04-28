package com.lomo.data.local.dao

/**
 * SQLite limits the number of host parameters (bind variables) in a single
 * prepared statement to 999 by default. Using 900 provides a safety margin
 * when multiple parameters share the same statement.
 */
internal const val ROOM_MAX_BIND_PARAMETER_COUNT = 900
