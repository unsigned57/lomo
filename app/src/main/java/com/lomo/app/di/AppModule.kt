package com.lomo.app.di

import com.lomo.app.media.AudioPlayerManager
import com.lomo.ui.media.AudioPlayerController
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideAudioPlayerController(manager: AudioPlayerManager): AudioPlayerController = manager
}
