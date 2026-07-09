package com.lomo.data.source

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal suspend fun directReadFileUri(uri: Uri): String? =
    withContext(Dispatchers.IO) {
        if (uri.scheme != "file") {
            return@withContext null
        }
        val path = uri.path ?: return@withContext null
        val file = File(path)
        if (file.exists()) {
            file.readTextBestEffortUtf8()
        } else {
            null
        }
    }
