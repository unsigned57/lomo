package com.lomo.domain.usecase

import com.lomo.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.Flow

open class ObserveDraftTextUseCase(
    private val preferencesRepository: PreferencesRepository,
) {
    open operator fun invoke(): Flow<String> = preferencesRepository.getDraftText()
}
