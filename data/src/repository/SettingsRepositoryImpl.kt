package com.lomo.data.repository
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.PreferencesRepository
class SettingsRepositoryImpl
constructor(
        directoryRepository: DirectorySettingsRepositoryImpl,
        preferencesRepository: PreferencesRepositoryImpl,
    ) : AppConfigRepository,
        DirectorySettingsRepository by directoryRepository,
        PreferencesRepository by preferencesRepository
