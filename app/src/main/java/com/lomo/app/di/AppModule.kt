package com.lomo.app.di

import com.lomo.app.repository.AppWidgetRepository
import com.lomo.domain.repository.WidgetRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds
    @Singleton
    abstract fun bindWidgetRepository(appWidgetRepository: AppWidgetRepository): WidgetRepository
}
