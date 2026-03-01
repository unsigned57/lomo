package com.lomo.domain.usecase

import com.lomo.domain.repository.ShareImageRepository

class PersistShareImageUseCase
    constructor(
        private val repository: ShareImageRepository,
    ) {
        suspend operator fun invoke(
            pngBytes: ByteArray,
            fileNamePrefix: String = "memo_share",
        ): String = repository.storeShareImage(pngBytes = pngBytes, fileNamePrefix = fileNamePrefix)
    }
