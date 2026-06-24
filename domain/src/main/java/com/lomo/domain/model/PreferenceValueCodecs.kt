package com.lomo.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object PreferenceValueCodecs {
    const val DEFAULT_MEMO_ACTION_ORDER_SCOPE = "main"

    private const val MEMO_ACTION_ORDER_DELIMITER = "|"
    private const val INPUT_TOOLBAR_TOOL_ORDER_DELIMITER = "|"
    private const val SIDEBAR_TAG_ORDER_DELIMITER = "|"

    private val memoActionOrderScopeJson =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun decodeMemoActionOrder(serialized: String): List<String> =
        serialized
            .split(MEMO_ACTION_ORDER_DELIMITER)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()

    fun encodeMemoActionOrder(actionOrder: List<String>): String =
        actionOrder
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .joinToString(MEMO_ACTION_ORDER_DELIMITER)

    fun normalizeMemoActionOrder(actionOrder: List<String>): List<String> =
        decodeMemoActionOrder(encodeMemoActionOrder(actionOrder))

    fun normalizeMemoActionOrderScope(scope: String): String? =
        scope.trim().takeIf(String::isNotEmpty)

    fun decodeMemoActionOrdersByScope(serialized: String): Map<String, List<String>> =
        if (serialized.isBlank()) {
            emptyMap()
        } else {
            // behavior-contract: silent-result-ok: corrupt JSON -> empty map; next save overwrites
            runCatching {
                memoActionOrderScopeJson
                    .decodeFromString<MemoActionOrdersByScopePayload>(serialized)
                    .orders
                    .mapNotNull { (scope, order) ->
                        val normalizedScope = normalizeMemoActionOrderScope(scope) ?: return@mapNotNull null
                        normalizedScope to normalizeMemoActionOrder(order)
                    }.toMap()
            }.getOrDefault(emptyMap())
        }

    fun encodeMemoActionOrdersByScope(ordersByScope: Map<String, List<String>>): String =
        memoActionOrderScopeJson.encodeToString(
            MemoActionOrdersByScopePayload(
                orders =
                    ordersByScope
                        .mapNotNull { (scope, order) ->
                            val normalizedScope = normalizeMemoActionOrderScope(scope) ?: return@mapNotNull null
                            val normalizedOrder = normalizeMemoActionOrder(order)
                            if (normalizedOrder.isEmpty()) {
                                null
                            } else {
                                normalizedScope to normalizedOrder
                            }
                        }.toMap(),
            ),
        )

    fun decodeInputToolbarToolOrder(serialized: String): List<String> =
        serialized
            .split(INPUT_TOOLBAR_TOOL_ORDER_DELIMITER)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()

    fun encodeInputToolbarToolOrder(toolOrder: List<String>): String =
        toolOrder
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .joinToString(INPUT_TOOLBAR_TOOL_ORDER_DELIMITER)

    fun decodeSidebarTagOrder(serialized: String): List<String> =
        serialized
            .split(SIDEBAR_TAG_ORDER_DELIMITER)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()

    fun encodeSidebarTagOrder(order: List<String>): String =
        order
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .joinToString(SIDEBAR_TAG_ORDER_DELIMITER)

    fun withMainMemoActionScope(
        mainOrder: List<String>,
        scopedOrders: Map<String, List<String>>,
    ): Map<String, List<String>> =
        buildMap {
            putAll(scopedOrders)
            if (mainOrder.isNotEmpty()) {
                put(DEFAULT_MEMO_ACTION_ORDER_SCOPE, mainOrder)
            }
        }

    @Serializable
    private data class MemoActionOrdersByScopePayload(
        val orders: Map<String, List<String>> = emptyMap(),
    )
}
