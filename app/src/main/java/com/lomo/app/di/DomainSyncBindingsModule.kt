package com.lomo.app.di

import com.lomo.domain.repository.UnifiedSyncProvider
import com.lomo.domain.usecase.GitSyncSettingsUseCase
import com.lomo.domain.usecase.LoadMemoRevisionHistoryUseCase
import com.lomo.domain.usecase.RestoreMemoRevisionUseCase
import com.lomo.domain.usecase.S3SyncSettingsUseCase
import com.lomo.domain.usecase.SyncConflictResolutionUseCase
import com.lomo.domain.usecase.SyncProviderRegistry
import com.lomo.domain.usecase.SyncReviewResolutionUseCase
import com.lomo.domain.usecase.WebDavSyncSettingsUseCase
import org.koin.dsl.module

val domainSyncModule = module {
    single<Set<UnifiedSyncProvider>> { getAll<UnifiedSyncProvider>().toSet() }
    single { SyncProviderRegistry(get()) }
    single { GitSyncSettingsUseCase(get(), get(), get(), get()) }
    single { WebDavSyncSettingsUseCase(get(), get(), get()) }
    single { S3SyncSettingsUseCase(get(), get(), get()) }
    single { LoadMemoRevisionHistoryUseCase(get()) }
    single { RestoreMemoRevisionUseCase(get()) }
    single { SyncConflictResolutionUseCase(get(), get()) }
    single { SyncReviewResolutionUseCase(get()) }
}
