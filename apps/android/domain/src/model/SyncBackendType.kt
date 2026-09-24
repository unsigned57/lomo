package com.lomo.domain.model

import java.util.Locale

enum class SyncBackendType {
    NONE,
    GIT,
    WEBDAV,
    S3,
    INBOX,

    /** Persisted value exists but names no backend this build understands: unavailable, not "off". */
    UNKNOWN,
    ;

    fun storageValue(): String = name.lowercase(Locale.ROOT)

    companion object {
        /**
         * Parses a persisted selection. Absent/blank means "never recorded" and maps to [NONE]
         * (the first-launch state); any other unrecognized value maps to [UNKNOWN] so callers can
         * surface an unavailable state instead of treating it as an explicit opt-out.
         */
        fun fromStorageValue(value: String?): SyncBackendType {
            if (value.isNullOrBlank()) return NONE
            return entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: UNKNOWN
        }
    }
}
