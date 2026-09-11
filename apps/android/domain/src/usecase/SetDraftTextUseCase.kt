package com.lomo.domain.usecase

import com.lomo.domain.repository.PreferencesRepository

open class SetDraftTextUseCase(
    private val preferencesRepository: PreferencesRepository,
) {
    open suspend operator fun invoke(text: String?) {
        preferencesRepository.setDraftText(text)
    }
}
