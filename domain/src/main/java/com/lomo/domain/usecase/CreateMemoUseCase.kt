package com.lomo.domain.usecase

import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.validation.MemoContentValidator
import javax.inject.Inject

class CreateMemoUseCase
    @Inject
    constructor(
        private val repository: MemoRepository,
        private val validator: MemoContentValidator,
    ) {
        suspend operator fun invoke(content: String) {
            validator.validateForCreate(content)
            repository.saveMemo(content)
        }
    }
