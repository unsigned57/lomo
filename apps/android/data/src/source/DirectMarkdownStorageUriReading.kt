package com.lomo.data.source

import android.net.Uri
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.withContext
import java.io.File

internal suspend fun directReadFileUri(
    uri: Uri,
    dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
): String? =
    withContext(dispatcherProvider.io) {
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
