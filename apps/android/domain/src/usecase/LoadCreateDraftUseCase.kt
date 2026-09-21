package com.lomo.domain.usecase

import com.lomo.domain.model.MemoCreateDraft
import com.lomo.domain.repository.MemoCreateDraftRepository

open class LoadCreateDraftUseCase(
    private val repository: MemoCreateDraftRepository,
) {
    open suspend operator fun invoke(): MemoCreateDraft? = repository.read()
}
