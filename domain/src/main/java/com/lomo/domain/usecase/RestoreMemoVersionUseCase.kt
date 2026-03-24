package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoVersion

class RestoreMemoVersionUseCase
(
        private val updateMemoContentUseCase: UpdateMemoContentUseCase,
    ) {
        suspend operator fun invoke(
            memo: Memo,
            version: MemoVersion,
        ) {
            updateMemoContentUseCase(memo, version.memoContent)
        }
    }
