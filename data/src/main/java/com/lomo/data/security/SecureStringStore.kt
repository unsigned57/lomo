package com.lomo.data.security

internal interface SecureStringStore {
    fun getString(key: String): String?

    fun putString(
        key: String,
        value: String?,
    )
}
