package com.lomo.data.share

import java.util.UUID

internal object LanSharePingProtocol {
    data class Identity(
        val uuid: String,
        val name: String,
    )

    fun encode(
        uuid: String,
        name: String,
    ): String {
        val canonicalUuid = requireNotNull(parseUuid(uuid)) { "LAN share device UUID must be valid." }
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty()) { "LAN share device name must not be blank." }
        require('\t' !in normalizedName && '\n' !in normalizedName && '\r' !in normalizedName) {
            "LAN share device name must fit one ping protocol field."
        }
        return "$PING_MARKER\t$canonicalUuid\t$normalizedName"
    }

    fun decode(body: String): Identity? {
        val fields = body.trimEnd('\r', '\n').split('\t')
        if (fields.size != PING_FIELD_COUNT || fields[0] != PING_MARKER) return null
        val uuid = parseUuid(fields[1]) ?: return null
        val name = fields[2].trim().takeIf(String::isNotEmpty) ?: return null
        return Identity(uuid = uuid, name = name)
    }

    fun parseUuid(value: String?): String? {
        val candidate = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return try {
            UUID.fromString(candidate).toString().takeIf { canonical ->
                canonical.equals(candidate, ignoreCase = true)
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private const val PING_MARKER = "lomo-share"
    private const val PING_FIELD_COUNT = 3
}
