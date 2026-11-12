package com.lomo.data.repository

import com.lomo.domain.model.PreferenceValueCodecs

internal object SettingsPreferenceCodecs {
    fun decodeInputToolbarToolOrder(serialized: String): List<String> =
        PreferenceValueCodecs.decodeInputToolbarToolOrder(serialized)

    fun encodeInputToolbarToolOrder(toolOrder: List<String>): String =
        PreferenceValueCodecs.encodeInputToolbarToolOrder(toolOrder)

    fun decodeSidebarTagOrder(serialized: String): List<String> =
        PreferenceValueCodecs.decodeSidebarTagOrder(serialized)

    fun encodeSidebarTagOrder(order: List<String>): String = PreferenceValueCodecs.encodeSidebarTagOrder(order)
}
