package com.lomo.domain.repository

interface ShareImageRepository {
    suspend fun storeShareImage(
        pngBytes: ByteArray,
        fileNamePrefix: String = "memo_share",
    ): String
}
