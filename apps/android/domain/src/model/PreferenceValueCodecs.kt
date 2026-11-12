package com.lomo.domain.model

object PreferenceValueCodecs {
    private const val INPUT_TOOLBAR_TOOL_ORDER_DELIMITER = "|"
    private const val SIDEBAR_TAG_ORDER_DELIMITER = "|"

    fun decodeInputToolbarToolOrder(serialized: String): List<String> = serialized
        .split(INPUT_TOOLBAR_TOOL_ORDER_DELIMITER)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

    fun encodeInputToolbarToolOrder(toolOrder: List<String>): String = toolOrder
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .joinToString(INPUT_TOOLBAR_TOOL_ORDER_DELIMITER)

    fun decodeSidebarTagOrder(serialized: String): List<String> = serialized
        .split(SIDEBAR_TAG_ORDER_DELIMITER)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

    fun encodeSidebarTagOrder(order: List<String>): String = order
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .joinToString(SIDEBAR_TAG_ORDER_DELIMITER)
}
