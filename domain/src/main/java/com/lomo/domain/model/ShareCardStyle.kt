package com.lomo.domain.model

enum class ShareCardStyle(val value: String) {
    WARM("warm"),
    CLEAN("clean"),
    DARK("dark"),
    ;

    companion object {
        fun fromValue(value: String): ShareCardStyle =
            entries.find { it.value == value } ?: CLEAN
    }
}
