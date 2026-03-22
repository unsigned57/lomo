package com.lomo.domain.usecase

import com.lomo.domain.repository.PreferencesRepository

class SetDraftTextUseCase(
    private val preferencesRepository: PreferencesRepository,
) {
    suspend operator fun invoke(text: String?) {
        preferencesRepository.setDraftText(text)
    }
}
