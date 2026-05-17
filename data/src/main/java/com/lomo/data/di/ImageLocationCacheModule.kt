package com.lomo.data.di

import com.lomo.data.local.MemoDatabase
import com.lomo.data.local.dao.ImageLocationCacheDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ImageLocationCacheModule {
    @Provides
    @Singleton
    fun provideImageLocationCacheDao(database: MemoDatabase): ImageLocationCacheDao = database.imageLocationCacheDao()
}
