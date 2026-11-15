package com.lomo.data.repository

import android.net.Uri
import androidx.core.net.toUri

internal fun String?.toPersistedUriOrNull(): Uri? {
    // Blank / missing cached URIs fall through to directory-relative reads; only non-empty
    // persisted values must be scheme-valid content:// or file:// identities.
    val value = this?.takeIf { it.isNotBlank() } ?: return null
    require(value.startsWith("content://") || value.startsWith("file://")) {
        "Persisted workspace URI must use content:// or file://"
    }
    return value.toUri()
}
