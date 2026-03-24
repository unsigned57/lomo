package com.lomo.data.repository

import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.PreferencesRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl
    @Inject
    constructor(
        directoryRepository: DirectorySettingsRepositoryImpl,
        preferencesRepository: PreferencesRepositoryImpl,
    ) : AppConfigRepository,
        DirectorySettingsRepository by directoryRepository,
        PreferencesRepository by preferencesRepository
