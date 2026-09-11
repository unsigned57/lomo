package com.lomo.data.local.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences

internal suspend fun DataStore<Preferences>.setOrRemove(
    key: Preferences.Key<String>,
    value: String?,
) {
    editPreferences {
        setOrRemove(key, value)
    }
}

internal fun MutablePreferences.setOrRemove(
    key: Preferences.Key<String>,
    value: String?,
) {
    if (value != null) {
        this[key] = value
    } else {
        remove(key)
    }
}

internal fun MutablePreferences.setOrRemove(
    key: Preferences.Key<Long>,
    value: Long?,
) {
    if (value != null) {
        this[key] = value
    } else {
        remove(key)
    }
}

internal fun MutablePreferences.setOrRemove(
    key: Preferences.Key<Int>,
    value: Int?,
) {
    if (value != null) {
        this[key] = value
    } else {
        remove(key)
    }
}

internal suspend fun DataStore<Preferences>.setOrRemoveIfBlank(
    key: Preferences.Key<String>,
    value: String?,
) {
    editPreferences {
        if (value.isNullOrBlank()) {
            remove(key)
        } else {
            this[key] = value
        }
    }
}
