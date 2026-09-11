package com.lomo.data.source

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okio.buffer
import okio.source

internal fun SafDocumentAccess.streamTextLinesFromUri(uri: Uri): Flow<String> =
    flow {
        val input = contentResolver.openInputStream(uri) ?: return@flow
        input.source().buffer().use { source ->
            while (true) {
                val line = source.readUtf8Line() ?: break
                emit(line)
            }
        }
    }
