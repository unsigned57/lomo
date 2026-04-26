package com.lomo.data.di

import com.lomo.data.local.MemoDatabase
import com.lomo.data.local.dao.DefaultMainListDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseListModule {
    @Provides
    @Singleton
    fun provideDefaultMainListDao(database: MemoDatabase): DefaultMainListDao = database.defaultMainListDao()
}
