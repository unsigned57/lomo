package com.lomo.app.di

import com.lomo.app.AndroidExternalAppCommandStore
import com.lomo.app.ExternalAppCommandStore
import com.lomo.app.media.AudioPlayerManager
import com.lomo.app.startup.AndroidDynamicShortcutPublisher
import com.lomo.app.startup.DynamicShortcutPublisher
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

    @Provides
    @Singleton
    fun provideDispatcherProvider(): com.lomo.domain.usecase.DispatcherProvider =
        com.lomo.domain.usecase.DefaultDispatcherProvider()

    @Provides
    @Singleton
    fun provideDynamicShortcutPublisher(
        publisher: AndroidDynamicShortcutPublisher,
    ): DynamicShortcutPublisher = publisher

    @Provides
    @Singleton
    fun provideExternalAppCommandStore(
        store: AndroidExternalAppCommandStore,
    ): ExternalAppCommandStore = store
}
