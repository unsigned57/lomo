package com.lomo.data.repository

import com.lomo.domain.model.PreferenceValueCodecs

internal object SettingsPreferenceCodecs {
    fun decodeMemoActionOrder(serialized: String): List<String> =
        PreferenceValueCodecs.decodeMemoActionOrder(serialized)

    fun encodeMemoActionOrder(actionOrder: List<String>): String =
        PreferenceValueCodecs.encodeMemoActionOrder(actionOrder)

    fun normalizeMemoActionOrder(actionOrder: List<String>): List<String> =
        PreferenceValueCodecs.normalizeMemoActionOrder(actionOrder)

    fun normalizeMemoActionOrderScope(scope: String): String? =
        PreferenceValueCodecs.normalizeMemoActionOrderScope(scope)

    fun decodeMemoActionOrdersByScope(serialized: String): Map<String, List<String>> =
        PreferenceValueCodecs.decodeMemoActionOrdersByScope(serialized)

    fun encodeMemoActionOrdersByScope(ordersByScope: Map<String, List<String>>): String =
        PreferenceValueCodecs.encodeMemoActionOrdersByScope(ordersByScope)

    fun decodeInputToolbarToolOrder(serialized: String): List<String> =
        PreferenceValueCodecs.decodeInputToolbarToolOrder(serialized)

    fun encodeInputToolbarToolOrder(toolOrder: List<String>): String =
        PreferenceValueCodecs.encodeInputToolbarToolOrder(toolOrder)

    fun decodeSidebarTagOrder(serialized: String): List<String> =
        PreferenceValueCodecs.decodeSidebarTagOrder(serialized)

    fun encodeSidebarTagOrder(order: List<String>): String =
        PreferenceValueCodecs.encodeSidebarTagOrder(order)

    fun withMainMemoActionScope(
        mainOrder: List<String>,
        scopedOrders: Map<String, List<String>>,
    ): Map<String, List<String>> =
        PreferenceValueCodecs.withMainMemoActionScope(mainOrder, scopedOrders)
}
