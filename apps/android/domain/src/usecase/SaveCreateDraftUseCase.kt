package com.lomo.domain.usecase

import com.lomo.domain.model.MemoCreateDraft
import com.lomo.domain.repository.MemoCreateDraftRepository

open class SaveCreateDraftUseCase(
    private val repository: MemoCreateDraftRepository,
) {
    open suspend operator fun invoke(content: String?) {
        if (content.isNullOrEmpty()) {
            repository.clear()
        } else {
            repository.write(MemoCreateDraft(content))
        }
    }
}
