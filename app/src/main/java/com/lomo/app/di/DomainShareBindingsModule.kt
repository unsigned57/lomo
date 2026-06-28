package com.lomo.app.di

import com.lomo.domain.repository.ShareImageRepository
import com.lomo.domain.usecase.ExtractShareAttachmentsUseCase
import com.lomo.domain.usecase.PersistShareImageUseCase
import com.lomo.domain.usecase.PrepareShareCardContentUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DomainShareBindingsModule {
    @Provides
    @Singleton
    fun providePrepareShareCardContentUseCase(): PrepareShareCardContentUseCase = PrepareShareCardContentUseCase()

    @Provides
    @Singleton
    fun provideExtractShareAttachmentsUseCase(): ExtractShareAttachmentsUseCase = ExtractShareAttachmentsUseCase()

    @Provides
    @Singleton
    fun providePersistShareImageUseCase(
        repository: ShareImageRepository,
    ): PersistShareImageUseCase = PersistShareImageUseCase(repository)
}
