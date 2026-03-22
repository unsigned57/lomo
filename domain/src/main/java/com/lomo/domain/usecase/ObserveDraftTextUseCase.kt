package com.lomo.domain.usecase

import com.lomo.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.Flow

class ObserveDraftTextUseCase(
    private val preferencesRepository: PreferencesRepository,
) {
    operator fun invoke(): Flow<String> = preferencesRepository.getDraftText()
}
