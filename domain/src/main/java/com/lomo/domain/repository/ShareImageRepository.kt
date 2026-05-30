package com.lomo.domain.repository

import java.io.OutputStream

interface ShareImageRepository {
    suspend fun storeShareImage(
        fileNamePrefix: String = "memo_share",
        writer: suspend (OutputStream) -> Unit,
    ): String
}
