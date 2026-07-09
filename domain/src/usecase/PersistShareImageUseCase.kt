package com.lomo.domain.usecase

import com.lomo.domain.repository.ShareImageRepository
import java.io.OutputStream

class PersistShareImageUseCase(
    private val repository: ShareImageRepository,
) {
    suspend operator fun invoke(
        fileNamePrefix: String = "memo_share",
        writer: suspend (OutputStream) -> Unit,
    ): String =
        repository.storeShareImage(
            fileNamePrefix = fileNamePrefix,
            writer = writer,
        )
}
