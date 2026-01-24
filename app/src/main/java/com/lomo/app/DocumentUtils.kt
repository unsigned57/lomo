package com.lomo.app

import android.content.Context
import android.net.Uri

object DocumentUtils {
    // Simplified path resolver for demo.
    // Real implementation needs to handle SAF tree URIs and potentially copy files
    // if direct file access isn't allowed, but user requested "Native" MD app.
    // Ideally we assume user picks a primary storage folder like /sdcard/Documents/Notes

    fun getPath(
        context: Context,
        uri: Uri,
    ): String? {
        // This is tricky with Scoped Storage.
        // For MVP, we might need MANAGE_EXTERNAL_STORAGE or just take the URI.
        // But the Repository expects a File path (String).
        // Let's assume for now we can get a path or use a work-around.

        // For a hacky MVP on API 30+:
        val path = uri.path ?: return null
        // /tree/primary:Documents/Notes -> /storage/emulated/0/Documents/Notes
        if (path.contains("primary:")) {
            return "/storage/emulated/0/" + path.substringAfter("primary:")
        }
        return path
    }
}
