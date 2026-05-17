package com.lomo.data.di

import com.lomo.data.local.DatabaseInitializer
import com.lomo.domain.repository.DatabaseInitializationRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseInitializationModule {
    @Provides
    @Singleton
    fun provideDatabaseInitializationRepository(
        initializer: DatabaseInitializer,
    ): DatabaseInitializationRepository = initializer
}
